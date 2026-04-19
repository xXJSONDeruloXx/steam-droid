package com.valvesoftware.steam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * SteamBackgroundService
 *
 * Wraps steamservice.so as an Android foreground Service.
 * Responsible for:
 *   - Loading all native .so files in dependency order
 *   - Starting the SteamService IPC server thread (nativeStartThread)
 *   - Maintaining a foreground notification so Android does not kill the process
 *   - Cleanly shutting down the IPC server on stop
 *
 * The IPC server listens on a Unix domain socket under getFilesDir()/Steam/steam.pipe
 * and libsteamclient.so (loaded in SteamMainActivity) connects to it for all Steam
 * client operations.
 */
class SteamBackgroundService : Service() {

    companion object {
        private const val TAG        = "SteamService"
        private const val CHANNEL_ID = "steam_background"
        private const val NOTIF_ID   = 1001

        // ---------------------------------------------------------------------------
        // JNI -- implemented in steamservice.so
        // ---------------------------------------------------------------------------

        /** Start the IPC server thread. Returns true on success. */
        @JvmStatic external fun nativeStartThread(steamDataPath: String): Boolean

        /** Return the native IPC server handle (opaque pointer as Long). */
        @JvmStatic external fun nativeGetIPCServer(): Long

        /** Graceful stop: finish pending jobs, close client connections, stop thread. */
        @JvmStatic external fun nativeStop()

        /** Immediate shutdown: no graceful drain. */
        @JvmStatic external fun nativeShutdown()

        // ---------------------------------------------------------------------------
        // Native library loading order matters -- dependencies first
        // ---------------------------------------------------------------------------
        init {
            try {
                // Load in reverse-dependency order
                System.loadLibrary("tier0_s")
                System.loadLibrary("vstdlib_s")
                System.loadLibrary("steamnetworkingsockets")
                System.loadLibrary("steamclient")
                System.loadLibrary("steamservice")
                Log.i(TAG, "All native libraries loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    private var serviceStarted = false

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        Log.i(TAG, "SteamBackgroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serviceStarted) {
            Log.d(TAG, "onStartCommand called but service already running")
            return START_STICKY
        }

        val steamDataPath = "${filesDir.absolutePath}/Steam"
        java.io.File(steamDataPath).mkdirs()
        Log.i(TAG, "Starting IPC server with data path: $steamDataPath")

        val ok = nativeStartThread(steamDataPath)
        if (ok) {
            serviceStarted = true
            updateNotification("Steam is running")
            Log.i(TAG, "IPC server started successfully")
        } else {
            Log.e(TAG, "nativeStartThread returned false -- stopping service")
            stopSelf(startId)
        }

        // START_STICKY: if killed, restart with null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped app from recents -- stop the service rather than leave it orphaned
        Log.i(TAG, "Task removed, stopping Steam service")
        nativeStop()
        stopSelf()
    }

    override fun onDestroy() {
        if (serviceStarted) {
            Log.i(TAG, "Service destroyed, calling nativeStop")
            nativeStop()
            serviceStarted = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // Notification helpers
    // ---------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Steam",
            NotificationManager.IMPORTANCE_LOW   // silent -- no sound/vibration
        ).apply {
            description = "Keeps Steam running in the background"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Steam")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_steam_small)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, SteamMainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_close,
                "Disconnect",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, SteamBackgroundService::class.java)
                        .setAction("ACTION_STOP"),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(status))
    }
}
