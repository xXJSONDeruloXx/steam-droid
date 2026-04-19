#!/usr/bin/env python3
"""
manifest-decoder.py
-------------------
Downloads and decompresses Steam client update packages from Valve's public CDN.

Supports both plain .zip and .vz (VZa LZMA-compressed) package formats.

VZa format (reverse engineered):
  [0:3]   "VZa"  -- magic
  [3]     flags byte
  [4:8]   CRC32 of decompressed output (little-endian uint32)
  [8:12]  LZMA dictionary size (little-endian uint32)
  [12:-N] raw LZMA compressed body (no standalone header; use standard props 0x5D)
  [-N:-2] footer bytes (N=6 for smaller files, N=10 for larger files)
  [-2:]   "zv"  -- terminator

Usage:
  python3 manifest-decoder.py --manifest <path> --packages <name> [<name>...] --outdir <dir>
  python3 manifest-decoder.py --decompress <file.vz> --out <file.zip>
  python3 manifest-decoder.py --list <manifest_path>
"""

import argparse
import hashlib
import lzma
import os
import re
import struct
import urllib.request
import zipfile

CDN_BASE = "https://client-update.akamai.steamstatic.com/"


# ---------------------------------------------------------------------------
# VZa decompression
# ---------------------------------------------------------------------------

def decompress_vz(data: bytes) -> bytes:
    """Decompress a VZa-format buffer and return the raw decompressed bytes."""
    if data[:3] != b"VZa":
        raise ValueError(f"Not a VZa stream (magic={data[:3]!r})")
    if data[-2:] != b"zv":
        raise ValueError(f"Missing 'zv' footer (last 2 bytes={data[-2:].hex()})")

    dict_size = struct.unpack_from("<I", data, 8)[0]

    # LZMA standalone header: props(1) + dictsize(4) + uncompressed_size(8=unknown)
    lzma_header = bytes([0x5D]) + struct.pack("<I", dict_size) + struct.pack("<Q", 0xFFFFFFFFFFFFFFFF)

    # Try footer sizes 6 and 10 -- LZMA stops at EOS marker either way
    for footer_len in (6, 10):
        body = data[12:-footer_len]
        try:
            result = lzma.decompress(lzma_header + body, format=lzma.FORMAT_ALONE)
            return result
        except lzma.LZMAError:
            continue

    raise RuntimeError("VZa decompression failed for all known footer sizes")


def decompress_vz_file(src_path: str, dst_path: str) -> int:
    with open(src_path, "rb") as f:
        data = f.read()
    result = decompress_vz(data)
    with open(dst_path, "wb") as f:
        f.write(result)
    return len(result)


# ---------------------------------------------------------------------------
# Manifest parsing  (Valve KeyValues -- simplified, no nesting beyond 2 levels)
# ---------------------------------------------------------------------------

def parse_manifest(path: str) -> dict:
    """
    Parse a Valve KeyValues manifest file and return a dict of package entries.
    Each entry: { 'file': str, 'size': int, 'sha2': str, 'zipvz': str|None,
                  'sha2vz': str|None, 'IsBootstrapperPackage': bool }
    """
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    packages = {}
    current_name = None
    current = {}

    for line in text.splitlines():
        line = line.strip()
        # Top-level key (package name)
        m = re.match(r'^"([^"]+)"\s*$', line)
        if m and line != '"linuxarm64"':
            if current_name and "file" in current:
                packages[current_name] = current
            current_name = m.group(1)
            current = {}
            continue
        # Key-value pair inside a block
        m = re.match(r'^"([^"]+)"\s+"([^"]*)"$', line)
        if m and current_name:
            key, val = m.group(1), m.group(2)
            if key == "size":
                current[key] = int(val)
            elif key == "IsBootstrapperPackage":
                current[key] = val == "1"
            else:
                current[key] = val

    if current_name and "file" in current:
        packages[current_name] = current

    return packages


# ---------------------------------------------------------------------------
# Download helpers
# ---------------------------------------------------------------------------

def download(url: str, dest_path: str, show_progress: bool = True) -> None:
    os.makedirs(os.path.dirname(dest_path) or ".", exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "Valve/Steam"})
    with urllib.request.urlopen(req) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        chunk = 65536
        with open(dest_path, "wb") as f:
            while True:
                buf = resp.read(chunk)
                if not buf:
                    break
                f.write(buf)
                downloaded += len(buf)
                if show_progress and total:
                    pct = downloaded * 100 // total
                    print(f"\r  {pct:3d}%  {downloaded//1024//1024}MB / {total//1024//1024}MB", end="", flush=True)
    if show_progress:
        print()


def verify_sha2(path: str, expected_hex: str) -> bool:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    actual = h.hexdigest()
    return actual.lower() == expected_hex.lower()


# ---------------------------------------------------------------------------
# Package fetching
# ---------------------------------------------------------------------------

def fetch_package(pkg_name: str, pkg_info: dict, outdir: str, prefer_vz: bool = True) -> str:
    """
    Download and decompress a package. Returns the path to the final .zip file.
    Prefers the .vz compressed variant if available and prefer_vz is True.
    """
    os.makedirs(outdir, exist_ok=True)
    zip_path = os.path.join(outdir, f"{pkg_name}.zip")

    # Return cached result
    if os.path.exists(zip_path):
        print(f"  [cache] {pkg_name}.zip already exists, skipping download")
        return zip_path

    use_vz = prefer_vz and "zipvz" in pkg_info

    if use_vz:
        vz_filename = pkg_info["zipvz"]
        vz_url = CDN_BASE + vz_filename
        vz_path = os.path.join(outdir, f"{pkg_name}.zip.vz")
        print(f"  Downloading {pkg_name} (vz)...")
        download(vz_url, vz_path)
        if "sha2vz" in pkg_info:
            if not verify_sha2(vz_path, pkg_info["sha2vz"]):
                print(f"  WARNING: SHA2 mismatch for {vz_filename}")
        print(f"  Decompressing {pkg_name}.vz...")
        decompress_vz_file(vz_path, zip_path)
        os.remove(vz_path)
    else:
        zip_filename = pkg_info["file"]
        zip_url = CDN_BASE + zip_filename
        print(f"  Downloading {pkg_name}...")
        download(zip_url, zip_path)
        if "sha2" in pkg_info:
            if not verify_sha2(zip_path, pkg_info["sha2"]):
                print(f"  WARNING: SHA2 mismatch for {zip_filename}")

    return zip_path


def extract_package(zip_path: str, extract_dir: str) -> list:
    """Extract a .zip, ignoring symlink errors (common on Windows). Returns list of extracted paths."""
    extracted = []
    os.makedirs(extract_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        for info in zf.infolist():
            try:
                zf.extract(info, extract_dir)
                extracted.append(os.path.join(extract_dir, info.filename))
            except Exception:
                pass  # symlinks fail on Windows -- skip silently
    return extracted


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def cmd_list(args):
    pkgs = parse_manifest(args.manifest)
    print(f"{'Package Name':<45} {'Size':>12}  {'VZ?':4}  {'Boot'}")
    print("-" * 75)
    total = 0
    for name, info in pkgs.items():
        sz = info.get("size", 0)
        total += sz
        has_vz = "yes" if "zipvz" in info else "no"
        boot = "(*)" if info.get("IsBootstrapperPackage") else ""
        print(f"  {name:<43} {sz:>12,}  {has_vz:4}  {boot}")
    print("-" * 75)
    print(f"  {'TOTAL':<43} {total:>12,}")


def cmd_download(args):
    pkgs = parse_manifest(args.manifest)
    targets = args.packages if args.packages else list(pkgs.keys())
    for name in targets:
        if name not in pkgs:
            print(f"WARNING: package '{name}' not found in manifest")
            continue
        print(f"[{name}]")
        zip_path = fetch_package(name, pkgs[name], args.outdir)
        if args.extract:
            extract_dir = os.path.join(args.outdir, name)
            files = extract_package(zip_path, extract_dir)
            print(f"  Extracted {len(files)} files to {extract_dir}")
        else:
            print(f"  Saved to {zip_path}")


def cmd_decompress(args):
    size = decompress_vz_file(args.decompress, args.out)
    print(f"Decompressed {args.decompress} -> {args.out} ({size:,} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Steam manifest decoder / package downloader")
    sub = parser.add_subparsers(dest="cmd")

    p_list = sub.add_parser("list", help="List all packages in a manifest")
    p_list.add_argument("manifest")

    p_dl = sub.add_parser("download", help="Download and optionally extract packages")
    p_dl.add_argument("manifest")
    p_dl.add_argument("--packages", nargs="+", metavar="NAME")
    p_dl.add_argument("--outdir", default="./steam_packages")
    p_dl.add_argument("--extract", action="store_true", help="Extract zip after download")
    p_dl.add_argument("--no-vz", dest="prefer_vz", action="store_false")

    p_dc = sub.add_parser("decompress", help="Decompress a single .vz file")
    p_dc.add_argument("decompress", metavar="FILE.vz")
    p_dc.add_argument("--out", required=True, metavar="FILE.zip")

    args = parser.parse_args()
    if args.cmd == "list":
        cmd_list(args)
    elif args.cmd == "download":
        cmd_download(args)
    elif args.cmd == "decompress":
        cmd_decompress(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
