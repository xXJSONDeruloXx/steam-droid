package com.valve.steam

/**
 * SteamBridge -- Kotlin wrapper around steam_bridge.so (our JNI layer).
 *
 * steam_bridge.so dlopen's steamservice.so at runtime so we avoid
 * declaring its C exports as external fun (which would require them
 * to be JNI-named, which they are not).
 */
object SteamBridge {

    init {
        System.loadLibrary("steam_bridge")
    }

    // Version string from the native layer -- used as smoke test
    external fun nativeGetVersion(): String

    // Load steamservice.so and resolve symbols via dlopen/dlsym
    external fun nativeLoadService(): Boolean

    // Start the IPC server thread; steamDataPath = app files dir + "/Steam"
    external fun nativeStartThread(steamDataPath: String): Boolean

    // Return the IPC server pointer as Long (0 = not started)
    external fun nativeGetIPCServer(): Long

    // Graceful stop
    external fun nativeStop()

    // Immediate shutdown + dlclose
    external fun nativeShutdown()
}
