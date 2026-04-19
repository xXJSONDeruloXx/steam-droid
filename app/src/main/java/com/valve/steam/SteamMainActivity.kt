package com.valve.steam

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

class SteamMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Chunk 1: just prove the build compiles and bridge loads
        val version = try { SteamBridge.nativeGetVersion() } catch (e: Throwable) { "load failed: $e" }
        android.util.Log.i("SteamMain", "SteamBridge version: $version")
        setContentView(WebView(this))
    }
}
