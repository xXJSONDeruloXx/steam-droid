package com.valve.steam

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File

class SteamMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = try {
            SteamBridge.nativeGetVersion()
        } catch (e: Throwable) {
            "load failed: $e"
        }

        val nativeLibDir = applicationInfo.nativeLibraryDir
        val steamserviceExists = File(nativeLibDir, "steamservice.so").exists()
        val steamclientExists = File(nativeLibDir, "libsteamclient.so").exists()

        Log.i("SteamMain", "SteamBridge version: $version")
        Log.i("SteamMain", "nativeLibraryDir=$nativeLibDir")
        Log.i("SteamMain", "steamservice.so exists=$steamserviceExists")
        Log.i("SteamMain", "libsteamclient.so exists=$steamclientExists")

        SteamBackgroundService.start(this)

        setContentView(
            TextView(this).apply {
                text = buildString {
                    appendLine("Steam Droid milestone test")
                    appendLine()
                    appendLine("bridge=$version")
                    appendLine("nativeLibraryDir=$nativeLibDir")
                    appendLine("steamservice.so=$steamserviceExists")
                    appendLine("libsteamclient.so=$steamclientExists")
                    appendLine()
                    appendLine("Foreground service start requested.")
                    appendLine("Use adb logcat | grep -E 'Steam(Main|Service|Bridge)' to verify load/start.")
                }
                setTextIsSelectable(true)
                textSize = 14f
                setPadding(32, 48, 32, 48)
            }
        )
    }
}
