# 01  Manifest Analysis

## Source File

**File:** `steam_client_publicbeta_linuxarm64`  
**Format:** Valve KeyValues (`.vdf`-style)  
**Manifest Version:** `1776387948`  
**Build Date:** April 15, 2026  
**Platform Key:** `linuxarm64`

The manifest is signed with both `kvsign2` (ECDSA) and `kvsignatures` (RSA)  Valve uses dual-signature verification to prevent tampered manifests from being accepted by the bootstrapper.

---

## All 32 Packages

Each entry lists the filename (with SHA1 hash suffix), uncompressed size, and whether a `.vz` (VZa LZMA-compressed) variant exists.

| Package Name | Uncompressed Size | Has `.vz`? | Notes |
|---|---|---|---|
| `tenfoot_images_all` | 6.3 MB |  | Big Picture Mode images |
| `steamui_websrc_all` | 30.0 MB (78MB actual) |  | React/webpack UI source  651 files |
| `resources_misc_all` | 4.1 MB |  | WAV sounds, TGA textures |
| `resources_hidpi_all` | 96 KB |  | @2x retina UI graphics |
| `resources_all` | 3.7 MB |  | Icons, avatars, VR assets |
| `strings_en_all` | 111 KB |  | English-only localization |
| `strings_all` | 3.7 MB (15MB) |  | All 29 languages |
| `public_all` | 29.4 MB (54MB) |  | 3,868 files: controller configs, fonts, SVGs |
| `bins_hardware_all` | 360 KB |  | Steam Deck hardware firmware (.fw files) |
| `steamui_websrc_sounds_all` | 4.5 MB |  | UI sound effects |
| `steamui_websrc_movies_all` | 8.8 MB |  | UI video assets |
| `bins_linuxarm64` | 64.5 MB (174MB) |  | Core Linux ARM64 binaries (steamclient.so, steamui.so, etc.) |
| `bins_sdk_linuxarm64` | 32.8 MB |  | Steam SDK binaries |
| `bins_codecs_linuxarm64` | 16.8 MB |  | Audio/video codec libraries |
| `bins_misc_linuxarm64` | 35.7 MB |  | Miscellaneous Linux ARM64 libs |
| `bins_hardware_linuxarm64` | 7.2 MB |  | Hardware-specific ARM64 binaries |
| `webkit_linuxarm64` | 119 MB |  | Bundled WebKit/CEF engine (Linux) |
| `miles_linuxarm64` | 342 KB |  | Miles Sound System filters/mixers |
| `sdl3_linuxarm64` | 16.0 MB |  | SDL3 for Linux ARM64 |
| `steam_linuxarm64` | 4.1 MB |  | **Bootstrapper** (`IsBootstrapperPackage: 1`) |
| `runtime_scout_linuxarm64` | 60.5 MB |  | Steam Runtime Scout container |
| `runtime_steamrt_linuxarm64` | 118 MB |  | Steam Runtime (SteamRT) container |
| `bins_steamrt_linuxarm64` | 103 MB |  | SteamRT-specific binaries |
| `bins_codecs_steamrt_linuxarm64` | 9.4 MB |  | SteamRT codec libs |
| `bins_misc_steamrt_linuxarm64` | 1.0 MB |  | SteamRT misc libs (libopenvr_api.so, libmiles.so) |
| `webkit_steamrt_linuxarm64` | **10 KB** |  | Stub/symlinks only  SteamRT uses host webkit |
| `sdl3_steamrt_linuxarm64` | **690 bytes** |  | Stub only  690 byte placeholder |
| `steam_steamrt_linuxarm64` | 4.4 MB |  | SteamRT bootstrapper variant |
| `webkit_linuxarm64_linuxarm64` | 106 MB |  | Second webkit variant (linuxarm64 host) |
| `sdl3_linuxarm64_linuxarm64` | 9.1 MB |  | SDL3 for linuxarm64 host variant |
| `codecs_linuxarm64_linuxarm64` | 10.2 MB |  | Codec variant |
| `bins_linuxarm64_linuxarm64` | 110 MB |  | Binary variant (host=linuxarm64) |
| `bins_sdk_linuxarm64_linuxarm64` | 15.0 MB |  | SDK variant |
| **`bins_androidarm64_linuxarm64`** | **17.0 MB (51MB)** |  |  **Android ARM64 core libraries** |
| `resource_linuxarm64_linuxarm64` | **2.3 KB** |  | Streaming client SDL overlay JSON configs |

---

## VZa Compression Format (Reverse Engineered)

Valve's `.vz` files use a custom container wrapping raw LZMA compressed data. The format was reverse-engineered during this analysis.

### Format Structure

```
Offset  Size  Field
------  ----  -----
0       3     Magic: "VZa"
3       1     Flags byte (observed: 0xD3, 0xB4, 0x2F, 0x74)
4       4     CRC32 of decompressed output (little-endian uint32)
8       4     LZMA dictionary size (little-endian uint32)
12      N     LZMA compressed body (raw, no LZMA standalone header)
-10     8     Footer: [CRC32_of_compressed(4)] [unknown(2)] [padding(2)]  (*)
-2      2     Terminator: "zv" (0x7A 0x76)
```

> (*) Footer length varies: files with flags `0xD3`/`0xB4` have a 6-byte footer; files with flags `0x2F`/`0x74` have a 10-byte footer. The LZMA EOS (end-of-stream) marker is embedded in the compressed data so the decompressor naturally stops at the right point regardless.

### Decompression Algorithm

To decompress a `.vz` file:
1. Verify magic bytes `VZa`
2. Read `dict_size` from bytes `[8:12]`
3. Build a synthetic LZMA standalone header: `0x5D` (standard props) + `dict_size` (4 bytes LE) + `0xFFFFFFFFFFFFFFFF` (unknown uncompressed size, 8 bytes)
4. Concatenate with the compressed body `data[12:-6]` (or `data[12:-10]` for larger files)
5. Decompress with LZMA FORMAT_ALONE

See [`research/manifest-decoder.py`](../research/manifest-decoder.py) for the full implementation.

---

## Package Naming Convention

The double-platform packages (e.g. `bins_linuxarm64_linuxarm64`) follow the pattern:
```
{content}_{guest_platform}_{host_platform}
```
- **Guest platform**: The architecture of the binary content
- **Host platform**: The host system running Steam

This allows Steam to ship e.g. x86 binaries that run on a Linux ARM64 host via Proton/box64. The `androidarm64_linuxarm64` package follows exactly this convention: **Android ARM64 content, delivered to a Linux ARM64 host**  meaning the Linux ARM64 Steam client is the delivery vehicle for Android ARM64 libraries.

---

## CDN Access

All packages are served from Valve's public Akamai CDN:
```
https://client-update.akamai.steamstatic.com/{filename_with_hash}
```

The filename includes the SHA1 hash as a suffix, serving as both a content identifier and integrity check. No authentication is required  these are the same files the Steam client bootstrapper downloads.
