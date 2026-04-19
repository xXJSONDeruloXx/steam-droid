#!/usr/bin/env python3
"""
elf-inspector.py
----------------
Inspect Android ARM64 ELF shared objects extracted from bins_androidarm64_linuxarm64.

Usage:
  python3 elf-inspector.py <file.so> [--strings] [--deps] [--exports] [--build-paths]
  python3 elf-inspector.py --all <directory>
"""

import argparse
import os
import re
import struct
import sys


# ---------------------------------------------------------------------------
# ELF constants
# ---------------------------------------------------------------------------

EI_CLASS_64     = 2
EM_AARCH64      = 183
PT_DYNAMIC      = 2
DT_NULL         = 0
DT_NEEDED       = 1
DT_STRTAB       = 5
DT_SYMTAB       = 6
DT_SONAME       = 14
SHT_DYNSYM      = 11
SHT_STRTAB      = 3
STB_GLOBAL      = 1
STB_WEAK        = 2
STT_FUNC        = 2


# ---------------------------------------------------------------------------
# ELF reader (64-bit little-endian only)
# ---------------------------------------------------------------------------

class ELF64:
    def __init__(self, path: str):
        with open(path, "rb") as f:
            self.data = f.read()
        self.path = path
        self._parse_header()

    def _u16(self, off): return struct.unpack_from("<H", self.data, off)[0]
    def _u32(self, off): return struct.unpack_from("<I", self.data, off)[0]
    def _u64(self, off): return struct.unpack_from("<Q", self.data, off)[0]
    def _i64(self, off): return struct.unpack_from("<q", self.data, off)[0]

    def _parse_header(self):
        d = self.data
        assert d[:4] == b"\x7fELF", "Not an ELF file"
        assert d[4] == EI_CLASS_64, "Not a 64-bit ELF"
        self.e_machine   = self._u16(18)
        self.e_phoff     = self._u64(32)
        self.e_shoff     = self._u64(40)
        self.e_phentsize = self._u16(54)
        self.e_phnum     = self._u16(56)
        self.e_shentsize = self._u16(58)
        self.e_shnum     = self._u16(60)
        self.e_shstrndx  = self._u16(62)

    def _read_cstr(self, offset: int) -> str:
        end = self.data.index(b"\x00", offset)
        return self.data[offset:end].decode("utf-8", errors="replace")

    # -- Section headers --

    def _section_headers(self):
        hdrs = []
        for i in range(self.e_shnum):
            off = self.e_shoff + i * self.e_shentsize
            hdrs.append({
                "sh_name":      self._u32(off),
                "sh_type":      self._u32(off + 4),
                "sh_offset":    self._u64(off + 24),
                "sh_size":      self._u64(off + 32),
                "sh_entsize":   self._u64(off + 56),
            })
        return hdrs

    def _shstrtab(self):
        hdrs = self._section_headers()
        s = hdrs[self.e_shstrndx]
        return self.data[s["sh_offset"]: s["sh_offset"] + s["sh_size"]]

    def section_by_name(self, name: str):
        shstr = self._shstrtab()
        for h in self._section_headers():
            n = shstr[h["sh_name"]: shstr.index(b"\x00", h["sh_name"])].decode()
            if n == name:
                return h
        return None

    # -- Dynamic segment --

    def _dynamic_entries(self):
        entries = []
        for i in range(self.e_phnum):
            off = self.e_phoff + i * self.e_phentsize
            p_type = self._u32(off)
            if p_type == PT_DYNAMIC:
                p_offset = self._u64(off + 8)
                p_filesz = self._u64(off + 32)
                for j in range(0, p_filesz, 16):
                    tag = self._i64(p_offset + j)
                    val = self._u64(p_offset + j + 8)
                    entries.append((tag, val))
                    if tag == DT_NULL:
                        break
                break
        return entries

    def dynamic_needed(self) -> list:
        """Return list of DT_NEEDED library names."""
        dyn = self._dynamic_entries()
        strtab_vaddr = next((v for t, v in dyn if t == DT_STRTAB), None)
        if strtab_vaddr is None or strtab_vaddr >= len(self.data):
            return []
        needed_offsets = [v for t, v in dyn if t == DT_NEEDED]
        result = []
        for off in needed_offsets:
            abs_off = strtab_vaddr + off
            if abs_off < len(self.data):
                try:
                    result.append(self._read_cstr(abs_off))
                except (ValueError, UnicodeDecodeError):
                    pass
        return result

    def soname(self) -> str:
        dyn = self._dynamic_entries()
        strtab_vaddr = next((v for t, v in dyn if t == DT_STRTAB), None)
        off = next((v for t, v in dyn if t == DT_SONAME), None)
        if strtab_vaddr and off is not None:
            abs_off = strtab_vaddr + off
            if abs_off < len(self.data):
                try:
                    return self._read_cstr(abs_off)
                except (ValueError, UnicodeDecodeError):
                    pass
        return ""

    # -- String extraction --

    def raw_strings(self, min_len: int = 6) -> list:
        pat = re.compile(rb"[ -~]{" + str(min_len).encode() + rb",}")
        return [m.group().decode("ascii", errors="ignore") for m in pat.finditer(self.data)]

    def build_paths(self) -> list:
        return sorted(set(s for s in self.raw_strings(10) if "/home/buildbot" in s))

    def android_metadata(self) -> dict:
        meta = {}
        # Check for .note.android.ident
        for s in self.raw_strings(4):
            if "Android" == s.strip():
                meta["android_marker"] = True
                break
        # NDK build ID from strings
        for s in self.raw_strings(8):
            if "NDK r" in s:
                meta["ndk_version"] = s.strip()
        return meta

    # -- Exports --

    def exported_functions(self) -> list:
        sec = self.section_by_name(".dynsym")
        strsec = self.section_by_name(".dynstr")
        if not sec or not strsec:
            return []
        results = []
        entry_size = sec["sh_entsize"] or 24
        count = sec["sh_size"] // entry_size
        strdata = self.data[strsec["sh_offset"]: strsec["sh_offset"] + strsec["sh_size"]]
        for i in range(count):
            off = sec["sh_offset"] + i * entry_size
            st_name  = self._u32(off)
            st_info  = self.data[off + 4]
            st_value = self._u64(off + 8)
            bind = (st_info >> 4) & 0xF
            typ  = st_info & 0xF
            if bind in (STB_GLOBAL, STB_WEAK) and typ == STT_FUNC and st_value != 0:
                try:
                    end = strdata.index(b"\x00", st_name)
                    name = strdata[st_name:end].decode("ascii", errors="ignore")
                    if name:
                        results.append(name)
                except (ValueError, IndexError):
                    pass
        return sorted(results)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def inspect_file(path: str, args):
    print(f"\n=== {os.path.basename(path)} ===")
    try:
        elf = ELF64(path)
    except Exception as e:
        print(f"  ERROR: {e}")
        return

    arch = "aarch64" if elf.e_machine == EM_AARCH64 else f"e_machine={elf.e_machine}"
    soname = elf.soname()
    size_mb = os.path.getsize(path) / 1024 / 1024

    print(f"  Size:    {size_mb:.1f} MB")
    print(f"  Arch:    {arch}")
    if soname:
        print(f"  SONAME:  {soname}")

    meta = elf.android_metadata()
    if meta.get("android_marker"):
        print(f"  Target:  Android (confirmed)")
    if "ndk_version" in meta:
        print(f"  NDK:     {meta['ndk_version']}")

    if args.deps or args.all_info:
        needed = elf.dynamic_needed()
        if needed:
            print(f"  Dynamic dependencies:")
            for lib in needed:
                print(f"    -> {lib}")
        else:
            print(f"  Dynamic dependencies: (none resolved)")

    if args.exports or args.all_info:
        exports = elf.exported_functions()
        if exports:
            print(f"  Exported functions ({len(exports)}):")
            for fn in exports[:50]:
                print(f"    {fn}")
            if len(exports) > 50:
                print(f"    ... and {len(exports)-50} more")
        else:
            print(f"  Exported functions: (none found in .dynsym)")

    if args.build_paths or args.all_info:
        paths = elf.build_paths()
        if paths:
            print(f"  Build paths ({len(paths)}):")
            seen_dirs = set()
            for p in paths:
                parts = p.split("/")
                d = "/".join(parts[:9]) if len(parts) >= 9 else p
                if d not in seen_dirs:
                    seen_dirs.add(d)
                    print(f"    {p[:100]}")

    if args.strings or args.all_info:
        strings = elf.raw_strings(8)
        interesting = [s for s in strings if any(k in s for k in
            ["Steam", "steam", "Android", "android", "JNI", "java",
             "Intent", "Activity", "libsteam", "ipc", "IPC", "pipe", "socket"])]
        seen = set()
        print(f"  Interesting strings:")
        for s in interesting:
            if s not in seen:
                seen.add(s)
                print(f"    {s[:100]}")
            if len(seen) >= 30:
                print(f"    ... (truncated)")
                break


def main():
    parser = argparse.ArgumentParser(description="ELF inspector for Steam Android libraries")
    parser.add_argument("files", nargs="*", help=".so files to inspect")
    parser.add_argument("--all-dir", metavar="DIR", help="Inspect all .so files in a directory")
    parser.add_argument("--deps",        action="store_true", help="Show dynamic dependencies")
    parser.add_argument("--exports",     action="store_true", help="Show exported functions")
    parser.add_argument("--build-paths", action="store_true", help="Show embedded build paths")
    parser.add_argument("--strings",     action="store_true", help="Show interesting strings")
    parser.add_argument("--all",  dest="all_info", action="store_true", help="Show everything")
    args = parser.parse_args()

    targets = list(args.files)
    if args.all_dir:
        for fname in sorted(os.listdir(args.all_dir)):
            if fname.endswith(".so"):
                targets.append(os.path.join(args.all_dir, fname))

    if not targets:
        parser.print_help()
        sys.exit(1)

    for path in targets:
        inspect_file(path, args)


if __name__ == "__main__":
    main()
