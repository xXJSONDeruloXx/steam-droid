# steam-droid

Reverse-engineering journal and implementation plan for a full native Steam Client Android APK.
Based on live analysis of Valve's `steam_client_publicbeta_linuxarm64` manifest (version `1776387948`, April 2026).

---

## TL;DR

Valve has already compiled the Steam client core as Android ARM64 native libraries and is shipping them in the public beta update manifest right now.

What exists publicly:
- `libsteamclient.so`
- `libsteamnetworkingsockets.so`
- `libtier0_s.so`
- `libvstdlib_s.so`
- `steamservice.so`

What this repo now provides:
- documentation of all findings
- a real Android Gradle project
- a JNI bridge (`libsteam_bridge.so`) that loads `steamservice.so` via `dlopen`/`dlsym`
- scripts to fetch and stage Valve's Android `.so` files from the CDN
- scripts to generate `local.properties` and build the debug APK

What is still missing publicly from Valve:
- an official Android wrapper layer
- Android WebView / Chromium integration for the full Steam UI
- Android `crashhandler.so` runtime used by `steamservice.so`
- other Android-side service dependencies such as `libsteam_api.so`, `libSDL3.so`, `libSDL3_image.so`, and likely `libsteamwebrtc.so`

---

## Quick start on another machine

This is the fastest path to a buildable APK on a second Windows machine.

### Prerequisites

Install these first:
- JDK 17
- Android SDK
- Android NDK 27.x or newer
- Python 3

Set this environment variable before building:
- `ANDROID_SDK_ROOT`

Optional but recommended:
- `JAVA_HOME`

Example:

```powershell
$env:ANDROID_SDK_ROOT = "C:\Users\you\AppData\Local\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk17.0.18_9"
```

### 1. Clone the repo

```powershell
git clone https://github.com/xXJSONDeruloXx/steam-droid.git
cd steam-droid
```

### 2. Generate `local.properties`

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\write-local-properties.ps1
```

### 3. Fetch and stage Valve's Android libraries

This downloads the current analyzed package directly from Valve's CDN, verifies SHA-256, extracts it, and stages the `.so` files into `app/src/main/jniLibs/arm64-v8a/`.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\fetch-valve-android-libs.ps1
```

### 4. Build the APK

```powershell
.\gradlew.bat assembleDebug
```

Built APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

### One-command path

If `ANDROID_SDK_ROOT` is already set, this does steps 2-4:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-debug.ps1 -FetchValveLibs
```

If `JAVA_HOME` is not set globally, pass it directly:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-debug.ps1 -FetchValveLibs -JavaHome "C:\Program Files\Amazon Corretto\jdk17.0.18_9"
```

---

## Current implementation status

Green and committed:
- Android Gradle project builds successfully
- Gradle wrapper is included
- `libsteam_bridge.so` compiles via NDK
- debug APK packages:
  - `libsteam_bridge.so`
  - Valve's 5 Android ARM64 Steam libraries when staged locally
- `steam_bridge.cpp` now correctly loads `steamservice.so` by its real SONAME
- local staging of Valve binaries is reproducible via scripts, not git-tracked blobs

Latest on-device bring-up status:
- `nativeLoadServiceAt()` is now proven on real ARM64 Android devices
- `SteamService_StartThread(...)` still does **not** complete successfully
- two concrete boot blockers were identified during live testing:
  - an OpenSSL / ARM hwcap probe path that executes unsupported SHA-512 instructions on some devices
  - a deeper startup dependency on `crashhandler.so` / `crashhandler004`
- WebView namespace shim for the full `window.SteamClient.*` API is still not implemented
- loading the actual Steam UI bundle inside Android WebView is still not implemented

---

## Repository structure

```text
steam-droid/
  README.md
  docs/
    01-manifest-analysis.md
    02-android-binaries.md
    03-ui-layer.md
    04-architecture.md
    05-missing-pieces.md
    06-implementation-plan.md
  research/
    manifest-decoder.py
    elf-inspector.py
    findings.json
  tools/
    fetch-valve-android-libs.ps1
    stage-valve-android-libs.ps1
    write-local-properties.ps1
    build-debug.ps1
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      cpp/
        CMakeLists.txt
        steam_bridge.cpp
      java/com/valve/steam/
        SteamBridge.kt
        SteamMainActivity.kt
        SteamBackgroundService.kt
        SteamBootReceiver.kt
      jniLibs/
      res/
    src/androidTest/
      SteamBridgeTest.kt
  gradlew
  gradlew.bat
  gradle/wrapper/
```

---

## Key findings at a glance

| Finding | Detail |
|---|---|
| Manifest version | `1776387948` |
| Android binary package | `bins_androidarm64_linuxarm64` |
| Target Android API | 21 |
| Toolchain evidence | built with Android NDK r29 |
| Build slave | `steam_rel_alt_androidarm64` |
| Main client library | `libsteamclient.so` (~36MB unstripped) |
| UI stack | webpack React app running in browser context |
| Android-specific assets | controller configs, touch layout, platform enums already present |
| Remaining public gaps | Java wrapper, Android WebView/CEF packaging, final integration |
| Estimated remaining effort | roughly 10-15% of total work |

---

## Documentation index

1. [Manifest Analysis](docs/01-manifest-analysis.md)
2. [Android Binaries](docs/02-android-binaries.md)
3. [UI Layer](docs/03-ui-layer.md)
4. [Architecture](docs/04-architecture.md)
5. [Missing Pieces](docs/05-missing-pieces.md)
6. [Implementation Plan](docs/06-implementation-plan.md)

---

## Disclaimer

All analysis is performed on publicly available files served from Valve's own CDN (`client-update.akamai.steamstatic.com`). No proprietary source code is reproduced. This is a research and reverse-engineering journal for educational purposes. Steam, the Steam logo, and all related marks are trademarks of Valve Corporation.
