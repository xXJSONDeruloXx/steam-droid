# 04  Architecture

## Full Steam Client Stack

```

                        ANDROID APK BOUNDARY                         

                                                                      
      
                STEAM UI  React/TypeScript SPA                     
                                                                    
    651 webpack JS bundles + CSS + assets                          
    Loaded from: file:///android_asset/steamui/index.html          
                                                                    
    window.SteamClient.*   @JavascriptInterface bridge        
                                                                    
    EUIMode: 4 (GamepadUI / fullscreen touch-friendly)             
    IN_MOBILE_WEBVIEW: true                                        
    navigator.userAgent: ...Android...                             
      
                                                                   
           WebView host              JSJava bridge                  
                                                                   
      
                    SteamMainActivity.kt                          
                                                                  
     Hosts WebView (System WebView or bundled Chromium)         
     addJavascriptInterface(SteamClientBridge, "SteamClient")    
     Handles steam:// deep links                                
     Manages lifecycle (pause  suspend SteamService)            
     Registers for Android Intents from SteamService            
      
                                                                   
            JNI layer                  IPC (Unix socket)             
                                                                   
      
     libsteamclient.so (36MB)       SteamBackgroundService     
                                                               
    Steam_LogOn/LogOff           steamservice.so (7MB)         
    Steam_CreateSteamPipe        SteamService_StartThread()    
    Full SteamAPI impl           SteamService_GetIPCServer()   
    Library management           Runs as foreground Service    
    Downloads, cloud saves       START_STICKY                  
    CEF/WebView integration      BOOT_COMPLETED receiver       
      
                                                                   
      
                   Supporting Libraries                            
                                                                  
    libsteamnetworkingsockets.so (7.7MB)                         
      Steam Datagram Relay, ICE/STUN/TURN, P2P encryption        
                                                                  
    libtier0_s.so (507KB)                                        
      Thread management, logging, profiling, memory              
                                                                  
    libvstdlib_s.so (683KB)                                      
      Valve stdlib: CUtlVector, CUtlHashMap, string utils        
      
                                                                     
      
                NOT YET IN MANIFEST (must be added)               
                                                                  
    libSDL3.so (Android)         display, input, audio          
    libSDL3_image.so (Android)   image loading                  
    libsteamwebrtc.so (Android)  voice, WebRTC                  
    libsteam_api.so (Android)    public Steam API wrapper       
      
                                                                     

                      ANDROID SYSTEM LAYER                           
  libandroid.so  liblog.so  libc.so  libdl.so  libm.so               
  Bionic libc, ALooper, AAssetManager, Android NDK APIs             

```

---

## IPC Model (steamservice.so  libsteamclient.so)

On desktop Linux, `steamservice` runs as a privileged system daemon and communicates with `steamclient.so` via Unix domain sockets or named pipes. On Android, both will run in the **same process** (or across the Activity/Service boundary using Android's standard IPC).

```
          Unix domain socket           
  SteamMainActivity     SteamBgService     
  (libsteamclient.so)          /data/data/com.valve.          (steamservice.so)  
                               steam/files/steam.pipe                            
  CIPCClient                                                   CIPCServer        
  CServerPipe          CWorkItemPipeline    CServerPipe[]     
                                        
```

The IPC server path for Android will resolve to the app's private data directory (verified by `/data/data/` path strings found in `libsteamclient.so`).

---

## WebView Integration  Two Paths

### Path A: Android System WebView (Simpler, ~50MB APK)

```
SteamMainActivity
     android.webkit.WebView
             loadUrl("file:///android_asset/steamui/index.html")
             addJavascriptInterface(bridge, "SteamClient")
             setJavaScriptEnabled(true)
             setDomStorageEnabled(true)
             WebViewClient / WebChromeClient
                     onConsoleMessage()  Logcat
                     onPermissionRequest()  Microphone, Camera
```

**Pros:** No extra binary size, always up-to-date (Android updates WebView via Play Store).  
**Cons:** Multiple popup windows are hard (each needs its own `WebView`), no direct CEF API control, slightly inconsistent CSS rendering across OEMs.

### Path B: Bundled Chromium / CEF (Full Fidelity, ~160MB APK)

```
SteamMainActivity
     org.chromium.* / io.github.denoland.* (embedded Chromium)
             CefBrowser (matches desktop CEF exactly)
             CefRenderProcessHandler  injects SteamClient.* into every frame
             CefDisplayHandler  handles title bar
             CefRequestHandler  intercepts steam:// URLs
```

**Pros:** Identical rendering to desktop, multi-window popups (overlay chat, game details), full DevTools support, exact same JS injection mechanism.  
**Cons:** +80120MB APK size, must be updated separately from Android OS.

**Valve's likely choice:** Path B  they bundle CEF on every other platform (Windows, Mac, Linux) and the `webkit_linuxarm64` package alone is 119MB. The missing `webkit_androidarm64` package in the manifest is the clearest sign of what's still being finished.

---

## Remote Play / Streaming Architecture

The `chord_mobile_touch.vdf` and the `streaming_client` binaries in `bins_linuxarm64` reveal that **Steam Remote Play is the primary V1 Android use case**.

```
Android Phone                          Gaming PC / Steam Machine
               
  Steam Android APK                    Steam Desktop Client   
                                                              
  [Library UI]                         [Game Running]         
                                                            
  Start Remote Stream  LAN/WAN   streaming_client      
                        (UDP, SDR)    (gameoverlayrenderer)  
  [Video Decode]                                             
  H.264/H.265/AV1                      [Capture + Encode]     
                                                             
  [Touch Controls]                     [Controller Input]     
  chord_mobile_touch    via Steam Input API    
               
```

The `sdloverlay_actions.json` and `sdloverlay_bindings_frame_controller.json` in the `resource_linuxarm64_linuxarm64` package define the SDL input action layer for the virtual gamepad overlay used during streaming  identical to what Steam Link uses today.

---

## Android Service Lifecycle

```
Boot:
  BOOT_COMPLETED broadcast  start SteamBackgroundService (if user chose auto-start)

App Launch:
  SteamMainActivity.onCreate()
     startForegroundService(SteamBackgroundService)
     bind to service
     SteamService_StartThread(dataPath)
     IPC server starts on Unix socket
     libsteamclient.so connects via IPC
     React UI loads, SteamClient.User.StartRefreshLogin()
     UI renders library

App Background:
  SteamMainActivity.onStop()
     SteamClient.User.PrepareForSystemSuspend()
     SteamBackgroundService stays alive as FOREGROUND service
     Notification: "Steam is running" with Disconnect action

App Killed:
  SteamBackgroundService.onTaskRemoved()
     SteamService_Stop()
     Clean shutdown or restart per user preference (START_STICKY)
```

---

## File System Layout on Android

```
/data/data/com.valvesoftware.steam/         app private dir
 files/
    Steam/
        config/
           loginusers.vdf             saved accounts
           SteamAppData.vdf           app state
           config.vdf                 settings
        steam.pipe                     Unix domain socket (IPC)
        registry.vdf                   simulated registry (POSIX Steam)
        logs/
            steam_*.log
 cache/
    appcache/                          downloaded content cache
 lib/
     libsteamclient.so
     steamservice.so
     libsteamnetworkingsockets.so
     libtier0_s.so
     libvstdlib_s.so
     libSDL3.so
     libsteamwebrtc.so

/sdcard/Android/data/com.valvesoftware.steam/files/
 Steam/
     steamapps/                         game installations (external storage)
         common/
         downloading/
```

The `clientdirectories.h` source file (visible in debug paths) is the component that resolves these paths at runtime. On Android it will use `context.getFilesDir()` and `context.getExternalFilesDir()`.

---

## APK Structure

```
steam.apk
 AndroidManifest.xml
 classes.dex                            Kotlin/Java compiled bytecode
 assets/
    steamui/                           Entire steamui_websrc extracted here
        index.html
        library.js
        chunk~2dcc5aaf7.js
        css/
        images/
        localization/
        ...
 res/
    drawable/                          App icon, notification icons
    layout/                            Activity layout XMLs
 lib/
    arm64-v8a/
        libsteamclient.so
        steamservice.so
        libsteamnetworkingsockets.so
        libtier0_s.so
        libvstdlib_s.so
        libSDL3.so                     To be provided
        libsteamwebrtc.so              To be provided
 resources.arsc
```
