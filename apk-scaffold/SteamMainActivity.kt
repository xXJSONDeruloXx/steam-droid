package com.valvesoftware.steam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage

/**
 * SteamMainActivity
 *
 * Hosts the Steam React UI inside an Android WebView.
 * The SteamClientBridge is injected as window.SteamClient so the JS side
 * of the UI can call into native code via the same API surface it uses on desktop.
 *
 * The steamservice.so IPC server is started via SteamBackgroundService before
 * the WebView begins loading.
 */
class SteamMainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: SteamClientBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during streaming; go fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Start the background IPC service before creating the WebView
        startForegroundService(Intent(this, SteamBackgroundService::class.java))

        webView = WebView(this).also { wv ->
            setContentView(wv)
            configureWebView(wv)
        }

        // Inject the SteamClient bridge -- must happen before loadUrl
        bridge = SteamClientBridge(this, webView)
        webView.addJavascriptInterface(bridge, "SteamClient")

        // Load the Steam UI from bundled assets
        // All steamui_websrc files are extracted to app/src/main/assets/steamui/
        webView.loadUrl("file:///android_asset/steamui/index.html")

        // Handle any steam:// intent that launched this activity
        handleIntent(intent)
    }

    private fun configureWebView(wv: WebView) {
        // Enable remote debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        wv.settings.apply {
            javaScriptEnabled                 = true
            domStorageEnabled                 = true
            databaseEnabled                   = true
            allowFileAccessFromFileURLs       = true
            allowUniversalAccessFromFileURLs  = true
            mediaPlaybackRequiresUserGesture  = false
            mixedContentMode                  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                         = WebSettings.LOAD_DEFAULT
            // Append Android identifier so IN_MOBILE_WEBVIEW detection works
            userAgentString                   = "$userAgentString SteamClientAndroid/1.0"
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return when {
                    url.startsWith("steam://")        -> { handleSteamURL(url); true }
                    url.startsWith("steambrowser://") -> { handleSteamURL(url); true }
                    else                               -> false
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // Allow microphone for voice chat
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // Pipe JS console to logcat
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val level = msg.messageLevel().name
                android.util.Log.println(
                    when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR   -> android.util.Log.ERROR
                        ConsoleMessage.MessageLevel.WARNING -> android.util.Log.WARN
                        else                                -> android.util.Log.DEBUG
                    },
                    "SteamJS",
                    "${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]"
                )
                return true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme?.startsWith("steam") == true) {
            handleSteamURL(uri.toString())
        }
    }

    /** Forward a steam:// URL into the JS layer via SteamClient.URL.ExecuteSteamURL */
    private fun handleSteamURL(url: String) {
        val escaped = url.replace("'", "\\'")
        webView.evaluateJavascript(
            "window.SteamClient?.URL?.ExecuteSteamURL?.('$escaped');",
            null
        )
    }

    override fun onPause() {
        super.onPause()
        // Tell the client to flush state before going to background
        webView.evaluateJavascript(
            "window.SteamClient?.User?.PrepareForSystemSuspend?.();",
            null
        )
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.evaluateJavascript(
            "window.SteamClient?.User?.ResumeSuspendedGames?.();",
            null
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Minimize to background rather than destroying the activity --
            // the SteamBackgroundService keeps running
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
