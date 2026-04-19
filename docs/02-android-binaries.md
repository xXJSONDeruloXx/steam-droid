# 02  Android Binaries Deep Dive

## Package: `bins_androidarm64_linuxarm64`

- **Compressed size:** 17,881,606 bytes (17.8 MB)  `.vz` variant: 8,763,442 bytes (8.4 MB)
- **Decompressed ZIP size:** 51.1 MB
- **SHA2:** `3511a7d5ed360234019e54f76f9010b3d96471d618b83b1176592f8504106cfb`
- **Download URL:** `https://client-update.akamai.steamstatic.com/bins_androidarm64_linuxarm64.zip.0f9e61801f1f37426f029181aed25d465dfdb5df`

---

## Extracted File Listing

```
androidarm64/libsteamclient.so            36,700,160 bytes  (35.0 MB)
androidarm64/libsteamnetworkingsockets.so  8,073,216 bytes  ( 7.7 MB)
androidarm64/steamservice.so              7,340,032 bytes  ( 7.0 MB)
androidarm64/libvstdlib_s.so                699,392 bytes  (683 KB)
androidarm64/libtier0_s.so                  519,168 bytes  (507 KB)
```

Total: **5 shared objects**, **~51 MB** unstripped (debug symbols present).

---

## ELF Headers (All Five Libraries)

```
Format:  ELF 64-bit LSB shared object
Arch:    ARM aarch64
Version: 1 (SYSV)
Linking: Dynamically linked
Target:  for Android 21
Toolchain: Built by NDK r29 (14206865)
Stripped: NO  debug info present
```

All five `.so` files are confirmed as genuine Android binaries via:
- `e_machine = EM_AARCH64 (183)`
- `.note.android.ident` ELF section present
- `__android_log_print` / `__android_log_vprint` imported symbols
- Minimum SDK version = 21 (Android 5.0 Lollipop)

---

## Build System Evidence

Debug paths embedded in all five libraries:

```
/home/buildbot/buildslave/steam_rel_alt_androidarm64/build/src/
    SteamServiceClient/serviceengine.cpp
    SteamServiceClient/servicemodulemanagerasync.cpp
    SteamServiceClient/servicemodulemanagerbase.cpp
    SteamServiceClient/serviceprocessmonitor.cpp
    clientcommon/generated_proto/webuimessages.pb.cc
    clientdll/ClientJobRemoteStorageSync.cpp
    common/RateLimiter.h
    common/clientdirectories.h
    common/completionportmanager.cpp
    common/completionportmanager_posix.cpp
    common/crypto_25519_donna.cpp
    common/crypto_digest_openssl.cpp
    common/crypto_rsa_openssl.cpp
    common/environmentmanager.cpp
    common/ipcserver.cpp
    common/pipes.cpp
    common/platform_sockets_posix.cpp
    common/processpipe_posix.cpp
    common/registry.cpp
    overlay/common/ipcposix.cpp
    public/html/htmlprotobuf.cpp
    steamnetworkingsockets/clientlib/...
    tier0/cpu.cpp
    tier1/KeyValues.cpp
    vstdlib/commandline.cpp
    mathlib/mathlib_base.cpp
    external/picojson/picojson.h
```

**Key observation:** Build slave path is `steam_rel_alt_androidarm64`  this is **not** a side experiment compiled from the Linux build. It is a separate, dedicated release build target (`rel_alt` = release alternate). This confirms Valve runs a continuous integration pipeline specifically for Steam on Android.

---

## Dynamic Library Dependencies

### `libsteamclient.so`
```
Imports:
  libandroid.so           Android NDK system library (Activity, Asset Manager)
  libdl.so                Dynamic linker
  libm.so                 Math library
  libSDL3.so              SDL3 (NOT in this package  must be provided separately)
  libSDL3_image.so        SDL3 image loading (NOT in this package)
  libsteamwebrtc.so       Steam WebRTC (NOT in this package  must be provided)
  libsteam_api.so         Steam public API (NOT in this package)
```

### `steamservice.so`
```
Imports:
  libandroid.so
  libdl.so
  libm.so
  libsteam_api.so         Steam public API (NOT in this package)
```

### `libsteamnetworkingsockets.so`
```
Imports:
  libandroid.so
  libdl.so
  libm.so
  libsteam_api.so
```

### `libtier0_s.so`
```
Imports:
  libc.so                 Android C library (Bionic)
  libdl.so
  liblog.so               Android logging (logcat)
```

### `libvstdlib_s.so`
```
Imports:
  libandroid.so
  libdl.so
  libm.so
  libtier0_s.so           Internal dependency (present in this package)
```

>  **Critical gap:** `libSDL3.so`, `libSDL3_image.so`, `libsteamwebrtc.so`, and `libsteam_api.so` for Android ARM64 are **not present in the manifest**. These must exist in Valve's internal builds. SDL3 for Android is open source (zlib license) and can be compiled trivially. WebRTC is more complex.

---

## Exported API Surface

### `steamservice.so`  C-style exports (IPC server lifecycle)

```c
SteamService_StartThread(const char* steam_path)  // Start IPC server thread
SteamService_GetIPCServer()                        // Get IPC server handle
SteamService_Stop()                                // Stop service
SteamService_Shutdown()                            // Full shutdown
```

These four functions form the Android Service  C++ bridge. They mirror the same interface used on Linux/macOS where `steamservice.so` runs as a system daemon.

### `libsteamclient.so`  Full Steam API

Selected exports confirming full client functionality (not just stub):

```c
// Core pipe/user management
Steam_CreateSteamPipe()
Steam_BReleaseSteamPipe()
Steam_CreateLocalUser()
Steam_CreateGlobalUser()
Steam_ConnectToGlobalUser()
Steam_ReleaseUser()
Steam_LogOn()
Steam_LogOff()
Steam_BLoggedOn()
Steam_BConnected()
Steam_SetLocalIPBinding()

// Game server functions
Steam_GSSendSteam2UserConnect()
Steam_GSSendSteam3UserConnect()
Steam_GSSendUserDisconnect()
Steam_GSUpdateStatus()
Steam_GSSetServerType()
Steam_GSLogOn() / Steam_GSLogOff()
Steam_GSBSecure()
Steam_GSGetSteamID()

// Internal plumbing
Steam_BGetCallback()
Steam_FreeLastCallback()
Steam_GetAPICallResult()
Steam_ReleaseThreadLocalMemory()
Steam_IsKnownInterface()
Steam_NotifyMissingInterface()

// Breakpad crash reporting (Android-compatible)
Breakpad_SteamMiniDumpInit()
Breakpad_SteamWriteMiniDumpUsingExceptionInfoWithBuildId()
Breakpad_SteamSendMiniDump()
Breakpad_SteamSetSteamID()
Breakpad_SteamSetAppID()
```

The presence of Breakpad crash reporting exports confirms this is a **production-quality build**, not a prototype.

---

## Cryptographic Modules Confirmed

From build paths, the following crypto modules are statically linked into the Android libraries:

- **OpenSSL**  RSA, AES, SHA-2 (`crypto_rsa_openssl.cpp`, `crypto_digest_openssl.cpp`)
- **Curve25519**  EC key exchange (`crypto_25519_donna.cpp`)
- **Google Protobuf**  Full protobuf runtime (confirmed by `google::protobuf::*` mangled symbols)
- **PicoJSON**  JSON parsing (`external/picojson/picojson.h`)

All of these are statically linked (no separate `.so` dependencies for them), which keeps the dependency list clean.

---

## IPC Architecture (from `steamservice.so` strings)

```
Source files: common/ipcserver.cpp, common/pipes.cpp, common/processpipe_posix.cpp

Classes found:
  CIPCServer        the server
  IIPCServer        server interface
  CServerPipe       per-client pipe
  IPipeWaiter       pipe event waiting
  CCrossProcessPipe  cross-process pipe
  CWorkItemPipeline  async work queue
  CPipeEvent         event signaling
  
Error strings:
  "CServerPipe not found"
  "BConnectToPipe - couldn't set TCP_NODELAY"
  "(no current pipe)"
  "Accept returned true but returned a NULL pipe"
```

The IPC system uses **POSIX pipes / Unix domain sockets** with TCP_NODELAY hints  identical to the Linux desktop implementation. On Android this works because the `libandroid.so` NDK layer exposes full POSIX socket support.

---

## Build Date

The `.so` files inside the ZIP have modification timestamp: **April 15, 2026 12:55 UTC**  
The manifest package was signed and published: **April 17, 2026 16:54 UTC**  
(Two days from build to CDN  consistent with Valve's regular release cadence)

---

## SHA1 Build IDs (from ELF notes)

```
libsteamclient.so:              6f1354254beb8240e13f94fef8d0ff7b0a3b470b
libsteamnetworkingsockets.so:   c3b36b3256b5e2e820a7856bc51d525bf5f435e8
libtier0_s.so:                  031a7efcfdbabd06d6081462b5d28231170a14b7
libvstdlib_s.so:                8a1fb3b0ec16fde2a9ad5ed56433c2f52e6667c1
steamservice.so:                375dde67ce965e346b17d2d916ed823d96f41b60
```

These build IDs can be used to match crash dumps to the correct debug symbol files (`*.so.dbg` files referenced in the dynamic string table but not yet published).
