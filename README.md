# steam-droid 

> **Reverse-engineering journal and implementation plan for a full native Steam Client Android APK.**
> Based on live analysis of Valve's `steam_client_publicbeta_linuxarm64` manifest (version `1776387948`, April 2026).

---

##  TL;DR

Valve has **already compiled the full Steam client core as Android ARM64 native libraries** and is shipping them in the public beta update manifest right now. The `.so` files are built with **NDK r29 targeting Android API 21**, on a dedicated CI build slave named `steam_rel_alt_androidarm64`. What doesn't exist yet (publicly) is the Android Java/Kotlin wrapper layer and the Chromium/WebView integration. This repo documents every finding and lays out exactly what it would take to complete the APK.

---

##  Repository Structure

```
steam-droid/
 README.md                         You are here
 docs/
    01-manifest-analysis.md       Full breakdown of the update manifest
    02-android-binaries.md        Deep dive into bins_androidarm64 .so files
    03-ui-layer.md                Steam React/JS UI analysis + SteamClient API
    04-architecture.md            Full stack architecture diagrams
    05-missing-pieces.md          What's absent and why it matters
    06-implementation-plan.md     Step-by-step APK build plan
 research/
    manifest-decoder.py           VZa compression format decoder (reverse engineered)
    elf-inspector.py              ELF .so dependency/string extractor
    findings.json                 Machine-readable summary of all findings
 apk-scaffold/
     AndroidManifest.xml           Reference manifest with all required permissions
     SteamMainActivity.kt          Skeleton Activity (WebView host)
     SteamBackgroundService.kt     Skeleton Android Service (steamservice.so wrapper)
     SteamClientBridge.kt          @JavascriptInterface bridge (~200 API methods)
     build.gradle                  NDK build config with jniLibs layout
```

---

##  Key Findings at a Glance

| Finding | Detail |
|---|---|
| **Manifest version** | `1776387948` (April 15, 2026 build) |
| **Android .so package** | `bins_androidarm64_linuxarm64`  17.8MB compressed, 51MB extracted |
| **Target Android API** | **21** (Android 5.0 Lollipop minimum) |
| **NDK version** | **r29** (14206865)  current as of 2025 |
| **Build slave name** | `steam_rel_alt_androidarm64`  dedicated active CI target |
| **Core library size** | `libsteamclient.so` = **36MB** unstripped |
| **UI stack** | 651-file webpack React app, running in CEF/WebView |
| **Android controller config** | `chord_android.vdf` + `chord_mobile_touch.vdf` already shipping |
| **Android platform enums** | `k_EPlatformTypeAndroid32=7`, `k_EPlatformTypeAndroid64=8` in all client JS |
| **Missing for APK** | SDL3 (Android), WebRTC (Android), CEF (Android), Java wrapper |
| **Estimated completion** | ~1015% of total effort remaining |

---

##  Documentation Index

1. **[Manifest Analysis](docs/01-manifest-analysis.md)**  All 32 packages, sizes, VZa decompression, what each contains
2. **[Android Binaries](docs/02-android-binaries.md)**  ELF analysis, symbols, build paths, dependency graph
3. **[UI Layer](docs/03-ui-layer.md)**  React UI, `SteamClient.*` JS API surface, mobile detection flags
4. **[Architecture](docs/04-architecture.md)**  Full stack diagram, IPC model, CEF integration
5. **[Missing Pieces](docs/05-missing-pieces.md)**  What's absent, why, and what Valve likely has internally
6. **[Implementation Plan](docs/06-implementation-plan.md)**  Phased roadmap to a working APK

---

##  Disclaimer

All analysis is performed on publicly available files served from Valve's own CDN (`client-update.akamai.steamstatic.com`). No proprietary source code is reproduced. This is a research and reverse-engineering journal for educational purposes. Steam, the Steam logo, and all related marks are trademarks of Valve Corporation.
