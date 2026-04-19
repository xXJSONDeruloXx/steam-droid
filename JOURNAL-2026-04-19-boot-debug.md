# Journal — 2026-04-19 — native boot debugging

## Goal
Determine why `SteamService_StartThread(...)` returns `false` even though:

- the APK builds and installs
- `steamservice.so` is packaged into the app
- `dlopen()` / symbol resolution succeed on device

## Devices tested

### Device A
- older Android ARM64 phone already attached earlier in the session

### Device B
- `66e837c8`
- `Retroid_Pocket_6`
- Android 13 (`kalama`)

## Manifest-backed runtime inputs confirmed

Directly downloadable from Valve's public manifest / package CDN and verified locally:

- `bins_androidarm64_linuxarm64`
  - `libsteamclient.so`
  - `libsteamnetworkingsockets.so`
  - `libtier0_s.so`
  - `libvstdlib_s.so`
  - `steamservice.so`
- `public_all`
  - includes `resource/registrykeys.vdf`
- `steamui_websrc_all`
- `resources_all`
- `strings_en_all`
- `bins_linuxarm64`
  - contains reference `ubuntu12_32/crashhandler.so`

## Major findings

### 1. Initial boot failure was a real CPU feature problem
On the older device, `strace` + tombstone analysis showed `steamservice.so` hit:

- `_armv8_sha512_probe`
- offset `0x2a43f0` in `steamservice.so`
- instruction `sha512su0 ...`

The phone reported ARM64 features including:
- `aes`
- `pmull`
- `sha1`
- `sha2`

but not the ARMv8 SHA-512 extension.

### 2. `OPENSSL_armcap=0` suppresses the SIGILL path
Setting `OPENSSL_armcap=0` before loading `steamservice.so` removed the illegal-instruction crash.
This confirms the native service was entering an OpenSSL ARM crypto capability probe during startup.

This workaround improves bring-up but does **not** make `SteamService_StartThread(...)` succeed.

### 3. `crashhandler.so` is part of the boot path
After suppressing the SHA-512 probe crash, `steamservice.so` consistently:

- loads `crashhandler.so`
- requests interface `crashhandler004`

This was reproduced on both tested devices.

### 4. Android `crashhandler.so` is not present in the public Android package
No Android `crashhandler.so` is present in `bins_androidarm64_linuxarm64`.

However, Valve does ship a Linux reference binary:
- `bins_linuxarm64 -> ubuntu12_32/crashhandler.so`

That confirms the component is real in Valve's runtime ecosystem, but the Android build is not
currently public in the manifest packages we inspected.

### 5. Placeholder crashhandler stubs are insufficient
Several `crashhandler.so` stub strategies were tested:

- null / minimal `CreateInterface` stubs
- static fake objects
- heap-backed factory / handler objects
- padded vtable experiments

These proved useful for mapping the call flow, but they did **not** unblock service startup.
Observed results included:

- acceptance of `crashhandler004`
- calls through specific vtable slots
- object destruction expectations
- native crashes when the fake interface layout / ownership model diverged from Valve's real one

### 6. Newer device confirms this is not just an old-phone issue
The Retroid Pocket 6 test showed:

- APK install: success
- app launch: success
- `steamservice.so` load: success
- `SteamService_StartThread(...)`: still returns `false`
- `crashhandler004` path: still hit
- native crash still occurs after deeper crashhandler interaction

So the remaining blocker is not merely the original CPU instruction issue.

## Best current diagnosis
There are now two distinct boot blockers established by live testing:

1. **CPU feature probing / OpenSSL ARM hwcap path**
   - mitigated by `OPENSSL_armcap=0`
2. **Missing or incompatible Android `crashhandler.so` runtime**
   - still unresolved
   - likely a real unpublished Valve Android dependency

## Current code state in support of debugging
Local debug instrumentation now includes:

- direct absolute-path load of `steamservice.so`
- detailed startup logging
- background-thread native service bring-up
- temporary startup delay to allow tracing attachment
- local experimental `steam_api` / `crashhandler` stub libraries for runtime probing

## Conclusion
The Android public beta manifest is enough to prove the Steam client core exists and to load the
native service library on-device, but it is **not yet sufficient to complete native boot**.

The most concrete currently missing official Android runtime component identified during bring-up is:

- **`crashhandler.so` implementing the `crashhandler004` interface path expected by `steamservice.so`**
