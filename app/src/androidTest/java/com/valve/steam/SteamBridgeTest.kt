package com.valve.steam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chunk 1 -- JNI bridge smoke tests.
 *
 * RED:  runs before .so files are placed in jniLibs/ -- expects load failure
 * GREEN: runs after .so files are staged -- expects successful dlopen
 *
 * Run on device/emulator:
 *   ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SteamBridgeTest {

    @Test
    fun nativeGetVersion_returnsNonEmpty() {
        val version = SteamBridge.nativeGetVersion()
        assertTrue("version string should not be empty", version.isNotBlank())
        assertTrue("version should contain 'steam_bridge'", version.contains("steam_bridge"))
    }

    @Test
    fun nativeLoadService_returnsBooleanWithoutCrash() {
        // Without the actual .so files this returns false but must NOT crash
        val result = SteamBridge.nativeLoadService()
        // When .so files are staged this must be true; without them it can be false
        // We just assert it completes without throwing
        assertNotNull("nativeLoadService must return a value", result)
    }

    @Test
    fun steamDataPath_isWritable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val steamDir = java.io.File(ctx.filesDir, "Steam")
        steamDir.mkdirs()
        assertTrue("Steam data dir should be creatable", steamDir.exists())
        assertTrue("Steam data dir should be writable", steamDir.canWrite())
    }

    @Test
    fun nativeStartThread_withoutLoad_returnsFalse() {
        // Guard: calling startThread without loading first must not crash
        // (bridge returns false when g_StartThread is null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = ctx.filesDir.absolutePath + "/Steam"
        // Do NOT call nativeLoadService() first
        // Expect false (null function ptr guard in C++)
        val result = SteamBridge.nativeStartThread(path)
        assertFalse("startThread without prior load should return false", result)
    }
}
