package com.valve.steam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class SteamBackgroundService : Service() {

    companion object {
        private const val TAG = "SteamService"
        private const val CHANNEL_ID = "steam_service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.valve.steam.action.START_SERVICE"
        private const val ACTION_STOP = "com.valve.steam.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SteamBackgroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SteamBackgroundService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile
    private var started = false

    @Volatile
    private var startInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received stop request")
                stopNativeService()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Starting Steam background service")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        )

        if (started || startInFlight) {
            Log.i(TAG, "Service start already in progress or complete; ignoring duplicate start")
            return START_STICKY
        }

        startInFlight = true
        val currentStartId = startId

        Thread({
            val steamDataDir = File(filesDir, "Steam").apply { mkdirs() }
            val nativeLibDir = applicationInfo.nativeLibraryDir

            try {
                Log.i(TAG, "nativeLibraryDir=$nativeLibDir")
                Log.i(TAG, "steamDataDir=${steamDataDir.absolutePath} exists=${steamDataDir.exists()} writable=${steamDataDir.canWrite()}")

                val loaded = SteamBridge.nativeLoadServiceAt(nativeLibDir)
                Log.i(TAG, "nativeLoadServiceAt -> $loaded")
                if (!loaded) {
                    updateNotification("Steam background service failed to load")
                    stopForegroundCompat()
                    stopSelfResult(currentStartId)
                    return@Thread
                }

                val ipcBefore = SteamBridge.nativeGetIPCServer()
                Log.i(TAG, "nativeGetIPCServer before start -> 0x${ipcBefore.toString(16)}")

                val startedOk = SteamBridge.nativeStartThread(steamDataDir.absolutePath)
                val ipcAfter = SteamBridge.nativeGetIPCServer()
                Log.i(TAG, "nativeStartThread -> $startedOk, ipcServerAfter=0x${ipcAfter.toString(16)}")

                if (startedOk) {
                    started = true
                    updateNotification("Steam background service running")
                } else {
                    updateNotification("Steam native start returned false")
                    stopNativeService()
                    stopForegroundCompat()
                    stopSelfResult(currentStartId)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Steam background service", t)
                updateNotification("Steam background service crashed during startup")
                stopNativeService()
                stopForegroundCompat()
                stopSelfResult(currentStartId)
            } finally {
                startInFlight = false
            }
        }, "SteamServiceStarter").start()

        return START_STICKY
    }

    override fun onDestroy() {
        stopNativeService()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        )
    }

    private fun stopNativeService() {
        try {
            if (started) {
                Log.i(TAG, "Stopping native Steam service")
                SteamBridge.nativeStop()
            }
            SteamBridge.nativeShutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "Error while stopping native Steam service", t)
        } finally {
            started = false
        }
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Steam Background Service", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
