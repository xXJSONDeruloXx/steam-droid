# 03  Steam UI Layer Analysis

## Overview

The Steam client UI is **100% a web application**  a React/TypeScript single-page app compiled via webpack, running inside a Chromium Embedded Framework (CEF) browser context. There is no native UI framework (no Qt, no GTK, no custom renderer). This architecture is **directly portable to Android WebView** with the right JS bridge.

---

## Package: `steamui_websrc_all`

- **Compressed:** 25.4 MB  **Decompressed:** 77.8 MB (VZa)
- **File count:** 651 files
- **Top-level entry:** `steamui/index.html`

### File Breakdown

```
steamui/
 index.html                   SharedJSContext root (loads library.js + library.css)
 sp.js                        Steam "shared process" main React entry point
 library.js                   Main library/store page bundle
 library.css
 chunk~2dcc5aaf7.js           Core shared chunk  SteamClient API, stores, models
 chunk~87fd721f7.js           Secondary shared chunk
 libraries/
    libraries~00299a408.js   Vendor bundle (React, MobX, etc.)
    libraries~2dcc5aaf7.js   Secondary vendor bundle
 [1007-9988].js               ~110 lazy-loaded page/feature chunks (numbered by webpack hash)
 css/                         Per-chunk CSS modules
 localization/                29 languages  4 namespaces = ~130 JSON-as-JS locale files
    steamui_english-json.js
    friendsui_english-json.js
    steampops_english-json.js
    shared_english-json.js
    ... (same for arabic, brazilian, bulgarian, czech, danish, dutch, ...)
 images/
    controller/              250+ controller button/action icons (PNG)
       controller_config_controller_android.png    Android controller image
    client_login_bg_grid.jpg
    ...
 changelist.txt               Build CL: 10596293
```

---

## `index.html`  Entry Point

```html
<!doctype html>
<html style="width: 100%; height: 100%">
<head>
  <title>SharedJSContext</title>
  <meta charset="utf-8">
  <script defer src="/libraries/libraries~00299a408.js"></script>
  <script defer src="/library.js"></script>
  <link href="/css/library.css" rel="stylesheet">
</head>
<body style="width: 100%; height: 100%; margin: 0; overflow: hidden;">
  <div id="root" style="height:100%; width: 100%"></div>
  <div style="display:none"></div>
</body>
</html>
```

This is the **SharedJSContext**  a single hidden WebView/browser page that hosts all of Steam's React state. Individual UI windows (library, store, friends, overlay) are separate browser views that communicate through this shared context. On Android, this maps naturally to a single `WebView` or multiple `WebView` instances sharing state via the bridge.

---

## `SteamClient.*` JavaScript API Surface

The `SteamClient` global object is injected by the C++ CEF layer (or Android `@JavascriptInterface` equivalent). It exposes **~200+ async/sync functions** organized into namespaces.

### Complete Namespace Inventory

```
SteamClient._internal
  .SetRightToLeftMode(bool)

SteamClient.Apps                           60+ methods
  .RunGame(appId, launchOpts)
  .CancelLaunch(appId)
  .GetCachedAppDetails(appId)
  .SetCachedAppDetails(appId, details)
  .RegisterForAppDetails(callback)
  .RegisterForAppOverviewChanges(callback)
  .GetAchievementsInTimeRange(appId, start, end)
  .GetMyAchievementsForApp(appId)
  .GetFriendAchievementsForApp(appId, steamId)
  .GetPlaytime(appId)
  .GetLaunchOptionsForApp(appId)
  .SetAppLaunchOptions(appId, opts)
  .SetAppCurrentLanguage(appId, lang)
  .GetAvailableCompatTools()
  .SetCustomArtworkForApp(appId, imageData)
  .GetScreenshotsInTimeRange(appId, start, end)
  .GetDownloadedWorkshopItems(appId)
  .InstallFlatpakAppAndCreateShortcut(path)
  .ListFlatpakApps()
  .OpenAppSettingsDialog(appId)
  .RaiseWindowForGame(appId)
  .BackupFilesForApp(appId, path)
  .GetCloudPendingRemoteOperations(appId)
  ... (and ~35 more)

SteamClient.Browser                        Web browser panel
  .OpenURL(url)

SteamClient.Cloud
  .ResolveAppSyncConflict(appId, choice)
  .RegisterForAppSyncedToCloud(callback)
  .RegisterForSyncFailure(callback)

SteamClient.Downloads
  .PauseAppDownload(appId)
  .ResumeAppDownload(appId)
  .MoveAppUpdateToTopOfQueue(appId)
  .GetDownloadOverview()
  .RegisterForDownloadItems(callback)
  .RegisterForDownloadOverview(callback)

SteamClient.FamilySharing
  .GetAvailableLoanerApps()
  .RegisterForKioskModeAvailabilityChanged(callback)

SteamClient.Friends
  .GetFriendPersonaName(steamId)
  .GetFriendRelationship(steamId)
  .GetFriendList()
  .InviteUserToGame(steamId, connectStr)
  .SendChatMessage(steamId, message)
  .SetPersonaName(name)

SteamClient.GameNotes
  .GetNotes(appId)
  .SetNotes(appId, content)
  .RegisterForNotesSynced(callback)

SteamClient.GameRecording
  .StartRecording(appId)
  .StopRecording()
  .GetRecordingDuration()

SteamClient.Input
  .ForceConfiguratorFocus(appId)
  .RegisterForControllerStateChanges(callback)
  .RegisterForActiveControllerChanges(callback)

SteamClient.InstallFolder
  .AddInstallFolder(path)
  .GetInstallFolders()
  .SetDefaultInstallFolder(folderId)

SteamClient.Messaging
  .RegisterForMessages(type, callback)
  .PostMessage(type, data)

SteamClient.Notifications
  .RegisterForNotifications(callback)

SteamClient.OpenVR
  .PathProperties.SetBoolPathProperty(path, value)

SteamClient.Overlay
  .RegisterForActivateGameOverlayRequests(callback)

SteamClient.Parental
  .GetParentalSettings()
  .RegisterForParentalSettingsChanges(callback)

SteamClient.RemotePlay
  .GetAvailableClientApps()
  .RegisterForClientListChanged(callback)
  .StopStream()

SteamClient.Screenshots
  .UploadLocalFileToScreenshotCloud(appId, file)

SteamClient.Settings
  .GetMonitorInfo()
  .GetCurrentSettings()
  .SetCurrentSettings(settings)
  .RegisterForSettingsChanges(callback)
  .SetSaveAccountCredentials(bool)
  .GetAccountSettings()

SteamClient.SharedConnection
  .AllocateLocallyUniqueConnection()
  .SendMsg(connection, type, data)
  .RegisterForMessages(connection, type, callback)
  .DestroyConnection(connection)

SteamClient.Storage
  .GetLocalPath(scope)
  .GetLocalPathFileList(scope)

SteamClient.Streaming
  .GetStreamingClientRunning()
  .RegisterForStreamingClientLaunchComplete(callback)

SteamClient.System
  .GetOSType()
  .GetSteamInstallFolder()
  .IsSteamInTournamentMode()
  .RegisterForOnResumeFromSuspend(callback)
  .UI.GetDesktopMode()
  .UI.GetGamepadUIActive()
  .UI.RegisterForFocusChangeEvents(callback)

SteamClient.UI
  .GetUIMode()                             // Returns: 4=GamepadUI, 7=DesktopUI, 0=Overlay
  .SetUIMode(mode)
  .EnsureMainWindowCreated()
  .NotifyAppInitialized()
  .RegisterForUIModeChanged(callback)
  .RegisterForStartupFinished(callback)
  .GetOSEndOfLifeInfo()
  .GetDesiredSteamUIWindows()

SteamClient.Updates
  .CheckForUpdates()
  .RegisterForUpdateStateChanges(callback)

SteamClient.URL
  .ExecuteSteamURL(url)
  .GetSteamURLList()
  .RegisterForRunSteamURL(protocol, type, callback)

SteamClient.User
  .StartLogin(username, password)
  .StartOffline()
  .StartRefreshLogin()
  .CancelRefreshLogin()
  .SetLoginCredentials(username, password, remember)
  .ForgetPassword()
  .ChangeUser()
  .RemoveUser(steamId)
  .GoOffline() / .GoOnline()
  .Reconnect()
  .ForceShutdown() / .StartShutdown(flags) / .CancelShutdown()
  .SignOutAndRestart()
  .GetLoginUsers()
  .ShouldShowUserChooser()
  .PrepareForSystemSuspend()
  .ResumeSuspendedGames()
  .AuthorizeMicrotxn(txnId)
  .RegisterForCurrentUserChanges(callback)
  .RegisterForLoginStateChange(callback)
  .RegisterForShutdownStart/Done/Failed/State(callback)

SteamClient.WebChat
  .GetLocalAvatarBase64()
  .GetPrivateConnectString()
  .GetPushToTalkEnabled()
  .SetPushToTalkEnabled(bool)
  .SetPushToTalkHotKey(key)
  .SetVoiceChatActive(bool)
  .SetVoiceChatStatus(status)
  .OpenURLInClient(url)
  .RegisterForFriendPostMessage(callback)
  .RegisterForPushToTalkStateChange(callback)
  .RegisterForUIModeChange(callback)

SteamClient.WebUITransport
  .GetTransportInfo()
  .NotifyTransportFailure()

SteamClient.Window
  .GetWindowDimensions()
  .ResizeTo(width, height)
  .MoveTo(x, y)
  .MoveToLocation(monitorIndex, x, y)
  .Minimize() / .HideWindow() / .ShowWindow() / .BringToFront()
  .ToggleFullScreen()
  .SetGamepadUIAutoDisplayScale()
  .SetGamepadUIManualDisplayScaleFactor(factor)
  .GetMousePositionDetails()
  .DefaultMonitorHasFullscreenWindow()
  .GetDefaultMonitorDimensions()
  .SetMinSize(w, h)
  .SetWindowFlashing(bool)
  .SetWindowIcon(iconData)
  .SetComposition(type, value)
  .SetKeyFocus()
  .IsWindowMinimized()
  .ProcessShuttingDown()
  .RestoreWindowSizeAndPosition()
```

---

## Android/Mobile Detection in the UI

### `IN_MOBILE_WEBVIEW` Flag

```javascript
// In chunk~2dcc5aaf7.js  navigation store initialization
const a = p("android", "force_android_view");

// Usage example: age-gating for mobile Android users
return i.useMemo(() => {
    const e = (0, a.VY)("forceallages");
    return !(!e || "0" === e) ||
           !(!n.TS.IN_MOBILE_WEBVIEW || !navigator.userAgent.match(/Android/));
}, []);
```

When `IN_MOBILE_WEBVIEW` is `true` (set by the C++ host layer), the UI enters a mobile-optimized path. The `force_android_view` URL parameter can also force this mode for testing in a desktop browser.

### Platform Type Enums

```javascript
// EPlatformType enum  Android is first-class
e[e.k_EPlatformTypeWindows  = 1] = "k_EPlatformTypeWindows"
e[e.k_EPlatformTypeOSX      = 2] = "k_EPlatformTypeOSX"
e[e.k_EPlatformTypeLinux    = 3] = "k_EPlatformTypeLinux"
e[e.k_EPlatformTypePS3      = 5] = "k_EPlatformTypePS3"
e[e.k_EPlatformTypeLinux32  = 6] = "k_EPlatformTypeLinux32"
e[e.k_EPlatformTypeAndroid32 = 7] = "k_EPlatformTypeAndroid32"  //  
e[e.k_EPlatformTypeAndroid64 = 8] = "k_EPlatformTypeAndroid64"  //  
e[e.k_EPlatformTypeIOS32    = 9] = "k_EPlatformTypeIOS32"
e[e.k_EPlatformTypeIOS64    = 10] = "k_EPlatformTypeIOS64"
```

### Device Version Strings

```javascript
case -500: return "Android";
case -499: return "Android 6.x";
case -498: return "Android 7.x";
case -497: return "Android 8.x";
case -496: return "Android 9.x";
case -600: return "iOS";
// ... etc.
```

These strings appear in the "last seen on" device type dropdown for login history and session management.

### Browser Type Detection

```javascript
if (/Macintosh/i.test(e) && /Safari/i.test(e))
    this.m_sBrowserID = "ios";
else if (/Android/i.test(e))
    this.m_sBrowserID = "android";   //  triggers Android-specific paths
else
    this.m_sBrowserID = "";
```

### Mobile-Specific Functions

```javascript
RestorePopupStateForMobile()   // Restores popup/modal state on mobile resume
// Used in: m_bRestoringPopups = true; GetPerContextChatData().CloseAllPopups()...
```

---

## UI Mode Architecture

```
EUIMode values:
  0 = Overlay         (in-game Steam overlay)
  1 = Unknown/Mini    (transitional)
  4 = GamepadUI       (Big Picture / Steam Deck mode)
  7 = DesktopUI       (standard windowed desktop client)
 -1 = Unset

Mobile/Android target: GamepadUI (4) or new dedicated mobile mode

Key functions:
  SteamClient.UI.GetUIMode()     returns current mode integer
  SteamClient.UI.SetUIMode(4)    switch to Big Picture / GamepadUI
  IsGamepadUIActive() { return 4 == this.m_eUIMode }
  IsDesktopUIActive() { return 7 == this.m_eUIMode }
```

The GamepadUI mode (4) is the most likely baseline for Android  it's already touch-friendly, fullscreen, and runs on Steam Deck with similar constraints to a mobile device.

---

## Controller Config: Android (`chord_android.vdf`)

```
controller_type: controller_android
Button A  xinput_button start
Button B  xinput_button select  
Right Bumper  controller_action SCREENSHOT
```

A minimal Android controller chord map  primarily mapping physical Android gamepad buttons to Steam actions.

## Controller Config: Mobile Touch (`chord_mobile_touch.vdf`)

```
controller_type: controller_mobile_touch
touch_layout: [binary blob  defines touch zones and gesture regions]

D-Pad North/South (with repeat)  VOLUME_UP / VOLUME_DOWN
D-Pad East / West                NEXT_TRACK / PREV_TRACK  
D-Pad Click                      PLAY
Left Bumper                      toggle_magnifier
Right Bumper                     SCREENSHOT
Button Menu                      SHOW_KEYBOARD
Button Escape                    Alt-Tab toggle
Button Back Left/Right           LEFT_ARROW / RIGHT_ARROW (app switcher)
Left Trigger edge                mouse_button RIGHT (with haptic_intensity=2)
Right Trigger edge               mouse_button LEFT (with haptic_intensity=2)
```

This config treats the phone itself as a "controller" with volume rockers, media buttons, and a touch overlay. The **haptic intensity** on trigger edges confirms this is designed for **Steam Link remote play** on a physical phone  the haptics give click feedback when using on-screen virtual triggers.

---

## Localization Coverage

The `localization/` folder in `steamui_websrc` contains four namespaces in 29 languages:

| Namespace | Description |
|---|---|
| `steamui_*-json.js` | Main Steam UI strings (library, store, settings) |
| `friendsui_*-json.js` | Friends list, chat, voice |
| `steampops_*-json.js` | Pop-up notifications, toasts |
| `shared_*-json.js` | Common strings used across namespaces |
| `reducedui_*-json.js` | Reduced/simplified UI mode (kiosk?) |

Full language list: arabic, brazilian, bulgarian, czech, danish, dutch, english, finnish, french, german, greek, hungarian, indonesian, italian, japanese, koreana, latam, norwegian, polish, portuguese, romanian, russian, schinese (simplified + `sc_` variant), spanish, swedish, tchinese, thai, turkish, ukrainian, vietnamese.
