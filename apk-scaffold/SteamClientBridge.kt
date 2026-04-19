package com.valvesoftware.steam

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONArray
import android.util.Log

/**
 * SteamClientBridge
 *
 * Exposes the SteamClient.* JavaScript API surface to the Steam React UI running
 * inside the Android WebView.
 *
 * Every public method annotated with @JavascriptInterface corresponds to one
 * function the JS layer calls on window.SteamClient. Methods are named
 * {Namespace}_{MethodName} and the JS side maps them via a shim in index.html
 * (or the React app reads them directly if the bridge is flat-namespaced).
 *
 * The full API inventory is documented in docs/03-ui-layer.md.
 * This file contains the structural skeleton + representative implementations.
 * All ~200 methods must be filled in to reach full parity with the desktop client.
 *
 * Threading note: @JavascriptInterface methods run on a background thread.
 * Use webView.post { } to run anything back on the UI thread.
 */
class SteamClientBridge(
    private val ctx: Context,
    private val webView: WebView
) {
    private val TAG = "SteamClientBridge"

    // Registered JS callbacks: callbackId -> JS function name
    private val callbacks = mutableMapOf<String, String>()

    /**
     * Fire a registered JS callback from the C++ / Java side.
     * The JS layer registers callbacks via RegisterFor* methods.
     */
    fun fireCallback(callbackId: String, vararg args: Any?) {
        val argsJson = args.joinToString(",") { arg ->
            when (arg) {
                null      -> "null"
                is String -> "\"${arg.replace("\"", "\\\"")}\""
                is Boolean, is Number -> arg.toString()
                else      -> "\"${arg.toString().replace("\"", "\\\"")}\""
            }
        }
        val cbRef = callbacks[callbackId] ?: return
        val js = "try { ($cbRef)($argsJson); } catch(e) { console.error('SteamClient callback error', e); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // =========================================================================
    // SteamClient._internal
    // =========================================================================

    @JavascriptInterface
    fun Internal_SetRightToLeftMode(rtl: Boolean) {
        Log.d(TAG, "SetRightToLeftMode: $rtl")
        // Could apply RTL layout direction to the WebView container if needed
    }

    // =========================================================================
    // SteamClient.UI
    // =========================================================================

    /** Returns EUIMode: 4 = GamepadUI (fullscreen/touch), 7 = DesktopUI */
    @JavascriptInterface
    fun UI_GetUIMode(): Int = 4

    @JavascriptInterface
    fun UI_SetUIMode(mode: Int) {
        Log.d(TAG, "SetUIMode: $mode")
    }

    @JavascriptInterface
    fun UI_NotifyAppInitialized() {
        Log.i(TAG, "App initialized")
        // Signal to C++ that the JS context is ready for IPC
    }

    @JavascriptInterface
    fun UI_EnsureMainWindowCreated() {}

    @JavascriptInterface
    fun UI_RegisterForUIModeChanged(callbackId: String) {
        callbacks["UIModeChanged"] = callbackId
    }

    @JavascriptInterface
    fun UI_RegisterForStartupFinished(callbackId: String) {
        callbacks["StartupFinished"] = callbackId
        // Fire immediately since on Android the "startup" happens synchronously
        fireCallback("StartupFinished")
    }

    @JavascriptInterface
    fun UI_GetOSEndOfLifeInfo(): String = "{}"

    @JavascriptInterface
    fun UI_GetDesiredSteamUIWindows(): String = "[]"

    // =========================================================================
    // SteamClient.User
    // =========================================================================

    @JavascriptInterface
    fun User_GetLoginUsers(): String {
        // Read loginusers.vdf from getFilesDir()/Steam/config/loginusers.vdf
        // Parse and return JSON array of { accountName, steamId, personaName, rememberPassword }
        return "[]"
    }

    @JavascriptInterface
    fun User_ShouldShowUserChooser(): Boolean = false

    @JavascriptInterface
    fun User_StartLogin(username: String, password: String) {
        Log.d(TAG, "StartLogin: $username")
        // Delegate to libsteamclient.so JNI
    }

    @JavascriptInterface
    fun User_StartRefreshLogin() {
        Log.d(TAG, "StartRefreshLogin")
    }

    @JavascriptInterface
    fun User_CancelRefreshLogin() {}

    @JavascriptInterface
    fun User_SetLoginCredentials(username: String, password: String, remember: Boolean) {}

    @JavascriptInterface
    fun User_ForgetPassword() {}

    @JavascriptInterface
    fun User_ChangeUser() {}

    @JavascriptInterface
    fun User_RemoveUser(steamId: String) {}

    @JavascriptInterface
    fun User_GoOffline() {}

    @JavascriptInterface
    fun User_GoOnline() {}

    @JavascriptInterface
    fun User_Reconnect() {}

    @JavascriptInterface
    fun User_ForceShutdown() { ctx.startService(Intent(ctx, SteamBackgroundService::class.java).setAction("ACTION_STOP")) }

    @JavascriptInterface
    fun User_StartShutdown(flags: Int) { User_ForceShutdown() }

    @JavascriptInterface
    fun User_CancelShutdown() {}

    @JavascriptInterface
    fun User_SignOutAndRestart() {}

    @JavascriptInterface
    fun User_PrepareForSystemSuspend() { Log.d(TAG, "PrepareForSystemSuspend") }

    @JavascriptInterface
    fun User_ResumeSuspendedGames() { Log.d(TAG, "ResumeSuspendedGames") }

    @JavascriptInterface
    fun User_RegisterForCurrentUserChanges(callbackId: String) {
        callbacks["CurrentUserChanges"] = callbackId
    }

    @JavascriptInterface
    fun User_RegisterForLoginStateChange(callbackId: String) {
        callbacks["LoginStateChange"] = callbackId
    }

    @JavascriptInterface
    fun User_RegisterForShutdownStart(callbackId: String) {}
    @JavascriptInterface
    fun User_RegisterForShutdownDone(callbackId: String) {}
    @JavascriptInterface
    fun User_RegisterForShutdownFailed(callbackId: String) {}
    @JavascriptInterface
    fun User_RegisterForShutdownState(callbackId: String) {}

    // =========================================================================
    // SteamClient.Apps
    // =========================================================================

    @JavascriptInterface
    fun Apps_GetCachedAppDetails(appId: Int): String = "{}"

    @JavascriptInterface
    fun Apps_SetCachedAppDetails(appId: Int, detailsJson: String) {}

    @JavascriptInterface
    fun Apps_RegisterForAppDetails(callbackId: String) {
        callbacks["AppDetails"] = callbackId
    }

    @JavascriptInterface
    fun Apps_RegisterForAppOverviewChanges(callbackId: String) {
        callbacks["AppOverviewChanges"] = callbackId
    }

    @JavascriptInterface
    fun Apps_RunGame(appId: Long, launchOption: Int) {
        Log.i(TAG, "RunGame: appId=$appId launchOption=$launchOption")
        // v1: initiate Remote Play stream for this appId
        // Future: launch native Android build if available
    }

    @JavascriptInterface
    fun Apps_CancelLaunch(appId: Long) {}

    @JavascriptInterface
    fun Apps_GetPlaytime(appId: Int): String = "{\"nPlaytimeForever\":0}"

    @JavascriptInterface
    fun Apps_GetLaunchOptionsForApp(appId: Int): String = "[]"

    @JavascriptInterface
    fun Apps_SetAppLaunchOptions(appId: Int, opts: String) {}

    @JavascriptInterface
    fun Apps_GetAvailableCompatTools(): String = "[]"

    @JavascriptInterface
    fun Apps_GetMyAchievementsForApp(appId: Int): String = "[]"

    @JavascriptInterface
    fun Apps_GetDownloadedWorkshopItems(appId: Int): String = "[]"

    @JavascriptInterface
    fun Apps_RegisterForDRMFailureResponse(callbackId: String) {}

    @JavascriptInterface
    fun Apps_RegisterForGameActionStart(callbackId: String) {}

    @JavascriptInterface
    fun Apps_RegisterForGameActionEnd(callbackId: String) {}

    @JavascriptInterface
    fun Apps_RequestIconDataForApp(appId: Int) {}

    @JavascriptInterface
    fun Apps_ReportLibraryAssetCacheMiss(appId: Int, assetType: String) {}

    // =========================================================================
    // SteamClient.Downloads
    // =========================================================================

    @JavascriptInterface
    fun Downloads_GetDownloadOverview(): String =
        "{\"nQueuedEntries\":0,\"bDownloading\":false}"

    @JavascriptInterface
    fun Downloads_RegisterForDownloadOverview(callbackId: String) {
        callbacks["DownloadOverview"] = callbackId
    }

    @JavascriptInterface
    fun Downloads_RegisterForDownloadItems(callbackId: String) {
        callbacks["DownloadItems"] = callbackId
    }

    @JavascriptInterface
    fun Downloads_PauseAppDownload(appId: Long) {}

    @JavascriptInterface
    fun Downloads_ResumeAppDownload(appId: Long) {}

    @JavascriptInterface
    fun Downloads_MoveAppUpdateToTopOfQueue(appId: Long) {}

    // =========================================================================
    // SteamClient.RemotePlay
    // =========================================================================

    @JavascriptInterface
    fun RemotePlay_GetAvailableClientApps(): String = "[]"

    @JavascriptInterface
    fun RemotePlay_RegisterForClientListChanged(callbackId: String) {
        callbacks["RemotePlayClientList"] = callbackId
    }

    @JavascriptInterface
    fun RemotePlay_StopStream() {}

    // =========================================================================
    // SteamClient.Settings
    // =========================================================================

    @JavascriptInterface
    fun Settings_GetCurrentSettings(): String = JSONObject().apply {
        put("strLanguage", "english")
        put("bRememberPassword", true)
        put("bSteamInputEnabled", false)
        put("nNotificationsCorner", 3)
    }.toString()

    @JavascriptInterface
    fun Settings_SetCurrentSettings(settingsJson: String) {
        Log.d(TAG, "SetCurrentSettings: $settingsJson")
    }

    @JavascriptInterface
    fun Settings_RegisterForSettingsChanges(callbackId: String) {}

    @JavascriptInterface
    fun Settings_GetMonitorInfo(): String = JSONObject().apply {
        val dm = ctx.resources.displayMetrics
        put("nWidth", dm.widthPixels)
        put("nHeight", dm.heightPixels)
        put("flDPIScale", dm.density)
    }.toString()

    // =========================================================================
    // SteamClient.Window
    // =========================================================================

    @JavascriptInterface
    fun Window_GetWindowDimensions(): String {
        val dm = ctx.resources.displayMetrics
        return "{\"width\":${dm.widthPixels},\"height\":${dm.heightPixels}}"
    }

    @JavascriptInterface
    fun Window_ResizeTo(width: Int, height: Int) {}

    @JavascriptInterface
    fun Window_MoveTo(x: Int, y: Int) {}

    @JavascriptInterface
    fun Window_Minimize() { (ctx as? Activity)?.moveTaskToBack(true) }

    @JavascriptInterface
    fun Window_HideWindow() { Window_Minimize() }

    @JavascriptInterface
    fun Window_ShowWindow() {}

    @JavascriptInterface
    fun Window_BringToFront() {}

    @JavascriptInterface
    fun Window_ToggleFullScreen() {}

    @JavascriptInterface
    fun Window_IsWindowMinimized(): Boolean = false

    @JavascriptInterface
    fun Window_SetGamepadUIAutoDisplayScale() {}

    @JavascriptInterface
    fun Window_SetGamepadUIManualDisplayScaleFactor(factor: Float) {}

    @JavascriptInterface
    fun Window_GetDefaultMonitorDimensions(): String = Window_GetWindowDimensions()

    @JavascriptInterface
    fun Window_DefaultMonitorHasFullscreenWindow(): Boolean = true

    @JavascriptInterface
    fun Window_GetMousePositionDetails(): String = "{\"x\":0,\"y\":0}"

    // =========================================================================
    // SteamClient.WebChat
    // =========================================================================

    @JavascriptInterface
    fun WebChat_GetLocalAvatarBase64(): String = ""

    @JavascriptInterface
    fun WebChat_GetPrivateConnectString(): String = ""

    @JavascriptInterface
    fun WebChat_GetPushToTalkEnabled(): Boolean = false

    @JavascriptInterface
    fun WebChat_SetPushToTalkEnabled(enabled: Boolean) {}

    @JavascriptInterface
    fun WebChat_SetVoiceChatActive(active: Boolean) {}

    @JavascriptInterface
    fun WebChat_SetVoiceChatStatus(status: Int) {}

    @JavascriptInterface
    fun WebChat_OpenURLInClient(url: String) {
        val js = "window.SteamClient?.Browser?.OpenURL?.('${url.replace("'", "\\'")}');"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    @JavascriptInterface
    fun WebChat_RegisterForFriendPostMessage(callbackId: String) {}

    @JavascriptInterface
    fun WebChat_RegisterForUIModeChange(callbackId: String) {}

    // =========================================================================
    // SteamClient.URL
    // =========================================================================

    @JavascriptInterface
    fun URL_ExecuteSteamURL(url: String) {
        Log.i(TAG, "ExecuteSteamURL: $url")
        // Parse and route steam:// URLs internally
    }

    @JavascriptInterface
    fun URL_GetSteamURLList(): String = "[]"

    @JavascriptInterface
    fun URL_RegisterForRunSteamURL(protocol: String, type: Int, callbackId: String) {}

    // =========================================================================
    // SteamClient.Updates
    // =========================================================================

    @JavascriptInterface
    fun Updates_CheckForUpdates() { Log.d(TAG, "CheckForUpdates") }

    @JavascriptInterface
    fun Updates_RegisterForUpdateStateChanges(callbackId: String) {}

    // =========================================================================
    // SteamClient.Notifications
    // =========================================================================

    @JavascriptInterface
    fun Notifications_RegisterForNotifications(callbackId: String) {
        callbacks["Notifications"] = callbackId
    }

    // =========================================================================
    // SteamClient.System
    // =========================================================================

    @JavascriptInterface
    fun System_GetOSType(): Int = 20  // k_EOSTypeAndroid

    @JavascriptInterface
    fun System_GetSteamInstallFolder(): String =
        "${ctx.filesDir.absolutePath}/Steam"

    @JavascriptInterface
    fun System_IsSteamInTournamentMode(): Boolean = false

    @JavascriptInterface
    fun System_RegisterForOnResumeFromSuspend(callbackId: String) {
        callbacks["ResumeFromSuspend"] = callbackId
    }

    // =========================================================================
    // SteamClient.WebUITransport
    // =========================================================================

    @JavascriptInterface
    fun WebUITransport_GetTransportInfo(): String =
        "{\"type\":\"android_jni\",\"version\":1}"

    @JavascriptInterface
    fun WebUITransport_NotifyTransportFailure() {
        Log.e(TAG, "WebUITransport failure reported by JS")
    }
}
