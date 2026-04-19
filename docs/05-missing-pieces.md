# 05 -- Missing Pieces

## What Is Not Yet in the Public Manifest

The five Android .so files prove the core client is built and shipping. The following components
are absent from the manifest but required for a complete APK.

## What *is* publicly obtainable from the manifest right now

These packages / files are useful and have now been verified against live device bring-up work:

- `bins_androidarm64_linuxarm64`
  - `libsteamclient.so`
  - `libsteamnetworkingsockets.so`
  - `libtier0_s.so`
  - `libvstdlib_s.so`
  - `steamservice.so`
- `steamui_websrc_all`
  - full React / webpack Steam UI bundle
- `public_all`
  - controller configs and other shared runtime resources
  - `resource/registrykeys.vdf` (confirmed present)
- `resources_all`
  - shared Steam icons / textures / cached resources
- `strings_en_all` / `strings_all`
  - localized strings for the client UI
- `bins_linuxarm64`
  - contains a **Linux** `ubuntu12_32/crashhandler.so`, which is useful as a reference binary
  - does **not** solve the missing Android crashhandler runtime

These packages are enough to prove the Android client core exists and to stage a serious prototype,
but they are **not** enough to complete native service startup on-device.

---

## 0. crashhandler.so (Android ARM64)

**Status:** Missing from the Android manifest packages. A Linux `crashhandler.so` exists in
`bins_linuxarm64` (`ubuntu12_32/crashhandler.so`), but no Android build is currently published.

**Why needed:** Live device testing shows `steamservice.so` loads `crashhandler.so` and requests
interface `crashhandler004` during `SteamService_StartThread(...)`. This is not a superficial
file existence check; the service expects a real object graph / vtable contract. Placeholder
stubs can move execution farther, but startup still dies in native code after crashhandler
interaction.

**Observed bring-up evidence:**
- `nativeLoadServiceAt(...)` succeeds on multiple Android devices
- `SteamService_StartThread(...)` proceeds far enough to load `crashhandler.so`
- `CreateInterface("crashhandler004")` is requested
- startup still aborts without a real Android crashhandler implementation

**Path to fix:** Valve would need to publish the Android crashhandler runtime, or the interface
would need to be fully reverse-engineered and reimplemented compatibly.

---

---

## 1. libSDL3.so (Android ARM64)

**Status:** Missing from manifest. Linux variant (sdl3_linuxarm64, 16MB) ships but no Android build.

**Why needed:** libsteamclient.so dynamically imports libSDL3.so and libSDL3_image.so. Without
these the dynamic linker will refuse to load libsteamclient.so.

**Path to fix:** SDL3 is open source (zlib license). Building for Android ARM64 is standard:
  - Clone https://github.com/libsdl-org/SDL
  - Use the Android CMake toolchain with NDK r29
  - Output: libSDL3.so + libSDL3_image.so

Valve almost certainly has internal Android SDL3 builds already given they maintain SDL upstream.

---

## 2. libsteamwebrtc.so (Android ARM64)

**Status:** Missing from manifest entirely for Android.

**Why needed:** libsteamclient.so imports libsteamwebrtc.so for voice chat and Steam Remote Play
low-latency streaming (WebRTC data channels). Without it voice and streaming are unavailable
but the rest of the client may still load if the symbol is weakly referenced.

**Path to fix:** Steam's WebRTC is a fork of Google's libwebrtc. Building for Android ARM64
requires the Chromium build system. This is the most complex missing dependency.

---

## 3. libsteam_api.so (Android ARM64)

**Status:** Missing. The public Steam SDK ships libsteam_api.so for Linux x86/x64 but not Android.

**Why needed:** Both steamservice.so and libsteamnetworkingsockets.so import it. It is the
public-facing Steamworks API wrapper that game developers link against.

**Path to fix:** Valve must publish this as part of any official Android Steamworks SDK.
The symbols it exports are fully documented in the Steamworks API reference.

---

## 4. WebKit / CEF for Android

**Status:** webkit_linuxarm64 is 119MB and ships for Linux. webkit_steamrt_linuxarm64 is a
10KB stub. There is no webkit_androidarm64 package.

**Why needed:** The entire Steam UI (651 JS files, React SPA) runs inside a browser context.
On desktop this is CEF (Chromium Embedded Framework). On Android, either:
  - Android System WebView must be used (already on device, no APK size cost), or
  - A full Chromium/CEF build for Android must be bundled (~120-180MB)

This is the single largest missing piece in the manifest. Its absence is the clearest
indicator that the Android client is not yet in final release state.

**Android System WebView as interim solution:**
  - Ships on every Android 5.0+ device (the same minimum API the .so files target)
  - Updated automatically via Google Play
  - Supports addJavascriptInterface() for the SteamClient.* bridge
  - Does not support multi-process CEF architecture or custom CefRenderProcessHandler
  - The Steam UI already detects IN_MOBILE_WEBVIEW and adapts behavior

---

## 5. Java / Kotlin APK Wrapper

**Status:** No .java or .kt files exist in any Steam package. This layer must be written.

**What is needed:**
  - SteamMainActivity -- hosts the WebView, handles lifecycle, deep links
  - SteamBackgroundService -- wraps steamservice.so as an Android foreground Service
  - SteamClientBridge -- ~200 @JavascriptInterface methods bridging JS to C++
  - AndroidManifest.xml -- permissions, intent filters, service declarations
  - build.gradle -- NDK integration, jniLibs packaging, APK signing
  - Notification channel for the persistent foreground service notification
  - BroadcastReceiver for BOOT_COMPLETED (auto-start option)
  - FileProvider for sharing screenshots / game clips

This is the smallest gap in terms of total code volume but requires the most
Android-specific knowledge. See docs/06-implementation-plan.md for the full build-out.

---

## 6. First-Run Asset Extraction

**Status:** Not yet designed.

The steamui_websrc assets (78MB uncompressed) need to be accessible to the WebView as
file:// URLs. Options:
  - Bundle as APK assets/ and serve via file:///android_asset/ -- simplest, no extraction
  - Bundle compressed and extract to getFilesDir() on first run -- allows delta updates
  - Serve from embedded HTTP server (localhost) -- most flexible, cleanest URL scheme

The Steam bootstrapper model (used on Linux) downloads and self-updates packages at runtime.
A full implementation would replicate this: the APK ships a minimal bootstrapper and downloads
the actual UI + client packages from the CDN on first launch, exactly like desktop Steam.

---

## 7. Steam Guard / 2FA on Android

**Status:** Partially handled -- the desktop client already has Steam Guard Mobile Authenticator
strings and the TOTP logic is inside libsteamclient.so.

**Gap:** On a new Android device, Steam Guard approval requires either:
  - An existing authenticated device (phone or desktop) to approve the new login
  - Email confirmation fallback
  - The Android client itself becoming a Steam Guard device (requires the existing
    Steam Mobile app's TOTP secret transfer mechanism)

The existing Steam Mobile app (separate app, separate codebase) currently owns the authenticator
functionality. The new full client will need to either integrate with or replace it.

---

## Summary Table

| Missing Component          | Complexity | Valve Has Internally? | Blocking? |
|----------------------------|------------|----------------------|-----------|
| crashhandler.so (Android)  | Medium     | Almost certainly yes | Yes       |
| libSDL3.so (Android)       | Low        | Almost certainly yes | Yes       |
| libSDL3_image.so (Android) | Low        | Almost certainly yes | Yes       |
| libsteamwebrtc.so (Android)| High       | Likely yes           | Partial   |
| libsteam_api.so (Android)  | Medium     | Yes                  | Yes       |
| CEF/WebView for Android    | Very High  | In progress          | Yes       |
| Java/Kotlin wrapper        | Medium     | Yes (unreleased)     | Yes       |
| First-run bootstrapper     | Medium     | Likely planned       | No (v1)   |
| Steam Guard integration    | Medium     | Partial              | No (v1)   |
