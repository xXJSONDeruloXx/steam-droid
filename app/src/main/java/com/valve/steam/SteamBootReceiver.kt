package com.valve.steam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SteamBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            android.util.Log.i("SteamBoot", "Boot completed, Steam auto-start placeholder")
        }
    }
}
