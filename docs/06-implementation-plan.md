# 06 -- Implementation Plan

## Goal

Produce a working Android APK that loads the full Steam client UI, authenticates a user,
displays their library, and supports Remote Play streaming. Game installation on-device is
a stretch goal dependent on game developers publishing Android builds to Steam.

---

## Phase 0 -- Environment Setup

### Requirements
  - Android Studio (latest stable)
  - NDK r29 (matching the shipped .so files exactly)
  - Android SDK API 21 minimum, API 35 compile target
  - Python 3.x (for manifest decoder / asset pipeline scripts)
  - Git LFS (the .so files are large)
  - A real Android ARM64 device or emulator (arm64-v8a AVD)

### Repository Layout
```
steam-droid/
  app/
    src/main/
      AndroidManifest.xml
      java/com/valve/steam/
        SteamMainActivity.kt
        SteamBackgroundService.kt
        SteamClientBridge.kt
        SteamBootReceiver.kt
        SteamDeepLinkActivity.kt
      assets/
        steamui/              <-- extracted from steamui_websrc_all.zip
      jniLibs/
        arm64-v8a/
          libsteamclient.so
          steamservice.so
          libsteamnetworkingsockets.so
          libtier0_s.so
          libvstdlib_s.so
          libSDL3.so          <-- build from source
          libSDL3_image.so    <-- build from source
          libsteamwebrtc.so   <-- obtain or stub
          libsteam_api.so     <-- obtain or stub
      res/
        layout/activity_main.xml
        drawable/
        values/
  build.gradle
  research/
  docs/
```

---

## Phase 1 -- Asset Pipeline

Download and extract the UI assets from the public CDN using the manifest decoder.

### Step 1.1 -- Download packages

```bash
python3 research/manifest-decoder.py \
  --manifest steam_client_publicbeta_linuxarm64 \
  --packages steamui_websrc_all strings_en_all public_all resources_all \
  --outdir /tmp/steam_assets
```

### Step 1.2 -- Extract to app/src/main/assets/steamui/

The steamui_websrc_all package extracts to a steamui/ folder. Copy its contents directly
into app/src/main/assets/steamui/. The WebView will load via:
  file:///android_asset/steamui/index.html

### Step 1.3 -- Copy .so files to jniLibs/arm64-v8a/

```bash
cp /tmp/steam_assets/androidarm64/*.so app/src/main/jniLibs/arm64-v8a/
```

### Step 1.4 -- Build SDL3 for Android

```bash
git clone https://github.com/libsdl-org/SDL --branch SDL3
cd SDL
mkdir build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build . --target SDL3
cp libSDL3.so ../../app/src/main/jniLibs/arm64-v8a/
```

---

## Phase 2 -- Android Manifest

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.valvesoftware.steam">

    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32"/>
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>

    <application
        android:label="Steam"
        android:icon="@drawable/ic_steam"
        android:theme="@style/Theme.Steam.Fullscreen"
        android:hardwareAccelerated="true"
        android:largeHeap="true"
        android:extractNativeLibs="true">

        <activity
            android:name=".SteamMainActivity"
            android:exported="true"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|uiMode"
            android:launchMode="singleTask"
            android:screenOrientation="unspecified">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="steam"/>
            </intent-filter>
        </activity>

        <service
            android:name=".SteamBackgroundService"
            android:exported="false"
            android:foregroundServiceType="dataSync"/>

        <receiver
            android:name=".SteamBootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED"/>
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

---

## Phase 3 -- SteamBackgroundService

Wraps steamservice.so as an Android foreground Service.

```kotlin
package com.valvesoftware.steam

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

class SteamBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "steam_service"
        const val NOTIF_ID   = 1

        // JNI declarations -- implemented in steamservice.so
        @JvmStatic external fun nativeStartThread(dataPath: String): Boolean
        @JvmStatic external fun nativeGetIPCServer(): Long
        @JvmStatic external fun nativeStop()
        @JvmStatic external fun nativeShutdown()

        init {
            System.loadLibrary("tier0_s")
            System.loadLibrary("vstdlib_s")
            System.loadLibrary("steamnetworkingsockets")
            System.loadLibrary("steamclient")
            System.loadLibrary("steamservice")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Steam is running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dataPath = "${filesDir.absolutePath}/Steam"
        java.io.File(dataPath).mkdirs()
        val ok = nativeStartThread(dataPath)
        if (!ok) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        nativeStop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Steam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_steam_small)
            .setOngoing(true)
            .build()
}
```

---

## Phase 4 -- SteamClientBridge (@JavascriptInterface)

The bridge exposes the SteamClient.* API to the WebView JavaScript context. Every method
the React UI calls on window.SteamClient must have a corresponding @JavascriptInterface method.

The full list of ~200 methods is derived from the SteamClient.* inventory in docs/03-ui-layer.md.
Below is the structural skeleton with representative examples from each namespace.

```kotlin
package com.valvesoftware.steam

import android.webkit.JavascriptInterface
import android.content.Context
import org.json.JSONObject

class SteamClientBridge(
    private val ctx: Context,
    private val webView: android.webkit.WebView
) {
    // Helper: fire a JS callback registered on the React side
    private fun fireCallback(callbackId: String, vararg args: Any?) {
        val argsJson = args.joinToString(",") {
            when (it) {
                null -> "null"
                is String -> "\"$it\""
                is Boolean, is Number -> it.toString()
                else -> it.toString()
            }
        }
        val js = "window.__steamCallbacks?.get('$callbackId')?.($argsJson)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // --------------- SteamClient.UI ---------------

    @JavascriptInterface
    fun UI_GetUIMode(): Int = 4  // Always return GamepadUI (4) on Android

    @JavascriptInterface
    fun UI_SetUIMode(mode: Int) { /* no-op or store mode */ }

    @JavascriptInterface
    fun UI_NotifyAppInitialized() { /* signal C++ that JS is ready */ }

    @JavascriptInterface
    fun UI_RegisterForUIModeChanged(callbackId: String) {
        // Store callbackId, fire when mode changes
    }

    // --------------- SteamClient.User ---------------

    @JavascriptInterface
    fun User_GetLoginUsers(): String {
        // Return JSON array of saved login users from loginusers.vdf
        return "[]"
    }

    @JavascriptInterface
    fun User_StartLogin(username: String, password: String) {
        // Delegate to libsteamclient.so via JNI
    }

    @JavascriptInterface
    fun User_StartRefreshLogin() { /* auto-login with saved credentials */ }

    @JavascriptInterface
    fun User_RegisterForLoginStateChange(callbackId: String) {
        // Store callback, fire when login state changes
    }

    // --------------- SteamClient.Apps ---------------

    @JavascriptInterface
    fun Apps_GetCachedAppDetails(appId: Int): String {
        return "{}"  // Return JSON app details
    }

    @JavascriptInterface
    fun Apps_RegisterForAppOverviewChanges(callbackId: String) { }

    @JavascriptInterface
    fun Apps_RunGame(appId: Long, launchOption: Int) {
        // On Android v1: launch Remote Play stream for this app
    }

    // --------------- SteamClient.Downloads ---------------

    @JavascriptInterface
    fun Downloads_GetDownloadOverview(): String = "{}"

    @JavascriptInterface
    fun Downloads_RegisterForDownloadOverview(callbackId: String) { }

    // --------------- SteamClient.RemotePlay ---------------

    @JavascriptInterface
    fun RemotePlay_GetAvailableClientApps(): String = "[]"

    @JavascriptInterface
    fun RemotePlay_RegisterForClientListChanged(callbackId: String) { }

    @JavascriptInterface
    fun RemotePlay_StopStream() { }

    // --------------- SteamClient.Settings ---------------

    @JavascriptInterface
    fun Settings_GetCurrentSettings(): String {
        return JSONObject().apply {
            put("strLanguage", "english")
            put("bRememberPassword", true)
        }.toString()
    }

    // --------------- SteamClient.WebChat ---------------

    @JavascriptInterface
    fun WebChat_GetLocalAvatarBase64(): String = ""

    @JavascriptInterface
    fun WebChat_SetVoiceChatActive(active: Boolean) { }

    // --------------- SteamClient.Window ---------------

    @JavascriptInterface
    fun Window_GetWindowDimensions(): String {
        val dm = ctx.resources.displayMetrics
        return "{\"width\":${dm.widthPixels},\"height\":${dm.heightPixels}}"
    }

    @JavascriptInterface
    fun Window_Minimize() { /* send app to background */ }

    @JavascriptInterface
    fun Window_SetGamepadUIAutoDisplayScale() { }

    // --------------- SteamClient._internal ---------------

    @JavascriptInterface
    fun Internal_SetRightToLeftMode(rtl: Boolean) { }
}
```

---

## Phase 5 -- SteamMainActivity

```kotlin
package com.valvesoftware.steam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.view.WindowManager

class SteamMainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: SteamClientBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen, keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        webView = WebView(this).also {
            setContentView(it)
            configureWebView(it)
        }

        // Start background service first
        startForegroundService(Intent(this, SteamBackgroundService::class.java))

        // Inject bridge and load UI
        bridge = SteamClientBridge(this, webView)
        webView.addJavascriptInterface(bridge, "SteamClient")
        webView.loadUrl("file:///android_asset/steamui/index.html")

        // Handle incoming steam:// deep link if launched from one
        handleIntent(intent)
    }

    private fun configureWebView(wv: WebView) {
        WebView.setWebContentsDebuggingEnabled(true)  // remove for release
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " SteamClientAndroid/1.0"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("steam://")) {
                    handleSteamURL(url)
                    return true
                }
                return false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "steam") handleSteamURL(uri.toString())
    }

    private fun handleSteamURL(url: String) {
        val js = "window.SteamClient?.URL?.ExecuteSteamURL('${url.replace("'","\\'")}');"
        webView.evaluateJavascript(js, null)
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("window.SteamClient?.User?.PrepareForSystemSuspend?.();", null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
```

---

## Phase 6 -- build.gradle

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.valvesoftware.steam'
    compileSdk 35

    defaultConfig {
        applicationId "com.valvesoftware.steam"
        minSdk 21
        targetSdk 35
        versionCode 1
        versionName "1776387948"  // matches manifest version

        ndk {
            abiFilters "arm64-v8a"
        }
    }

    sourceSets {
        main {
            jniLibs.srcDirs = ['src/main/jniLibs']
            assets.srcDirs  = ['src/main/assets']
        }
    }

    buildTypes {
        release {
            minifyEnabled false  // do not shrink -- JNI bridge methods must survive
            signingConfig signingConfigs.release
        }
        debug {
            debuggable true
            jniDebuggable true
        }
    }

    packagingOptions {
        // Keep .so files uncompressed for direct mmap loading
        doNotStrip "*/arm64-v8a/*.so"
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.0'
    implementation 'androidx.appcompat:appcompat:1.7.0'
}
```

---

## Phase 7 -- Testing Milestones

| Milestone | Success Criteria |
|---|---|
| M1 -- Service starts | SteamBackgroundService launches without crash; steamservice.so loads via System.loadLibrary |
| M2 -- WebView loads | index.html renders in WebView; no JS errors for missing SteamClient methods |
| M3 -- Bridge functional | SteamClient.UI.GetUIMode() returns 4 in JS console; DevTools accessible |
| M4 -- Login works | User can enter credentials; Steam Guard code accepted; library data loads |
| M5 -- Library renders | Game list visible with artwork; correct install/play state shown |
| M6 -- Remote Play | Stream starts to a PC running Steam; video decodes; input reaches host |
| M7 -- Notifications | Friend online alerts and game update toasts appear |
| M8 -- Deep links | steam://run/570 from browser launches Dota 2 Remote Play stream |

---

## Estimated APK Sizes

| Configuration | APK Size |
|---|---|
| Minimal (System WebView, no WebRTC) | ~50 MB |
| Standard (System WebView + all .so) | ~70 MB |
| Full (bundled Chromium + all .so) | ~200 MB |

All sizes before Play Store AAB compression, which typically reduces by a further 20-30%.

---

## Known Risks

1. SteamService IPC socket path -- must resolve to a writable path in getFilesDir().
   The clientdirectories.h logic may hardcode /home/... paths from the build slave. Needs testing.

2. Android 12+ background service restrictions -- foreground services must declare a type.
   dataSync is appropriate for the Steam service; add streamingApp for Remote Play.

3. 32-bit support -- the manifest only ships arm64 Android libraries. 32-bit Android devices
   (or apps forced to 32-bit) are not supported in this configuration.

4. WebView cross-origin -- loading file:///android_asset/ and then fetching https://store.steampowered.com
   hits mixed-content and CORS restrictions. The SteamClient bridge must intercept all
   network calls rather than letting the WebView make them directly.

5. Steam Guard on first login -- a new device login will require approval from an existing
   trusted device. This is not a bug but an expected friction point for a new install.
