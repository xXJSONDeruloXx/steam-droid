/*
 * steam_bridge.cpp
 *
 * Thin JNI wrapper that dlopen's Valve's steamservice.so (plain C exports,
 * not JNI-named functions) and bridges them to Kotlin/Java.
 *
 * Current goal:
 *   - load steamservice.so directly from the app nativeLibraryDir
 *   - resolve SteamService_* exports
 *   - start/stop the service thread from Android code
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <cstring>
#include <cstdlib>

#define TAG "SteamBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Function pointer types matching steamservice.so exports
// ---------------------------------------------------------------------------
typedef bool  (*SteamService_StartThread_t)(const char* steamDataPath);
typedef void* (*SteamService_GetIPCServer_t)();
typedef void  (*SteamService_Stop_t)();
typedef void  (*SteamService_Shutdown_t)();

static void* g_service_handle = nullptr;
static SteamService_StartThread_t  g_StartThread  = nullptr;
static SteamService_GetIPCServer_t g_GetIPCServer = nullptr;
static SteamService_Stop_t         g_Stop         = nullptr;
static SteamService_Shutdown_t     g_Shutdown     = nullptr;

// ---------------------------------------------------------------------------
// Resolve exported SteamService_* symbols from a loaded handle
// ---------------------------------------------------------------------------
static bool resolve_service_symbols() {
    g_StartThread  = (SteamService_StartThread_t)  dlsym(g_service_handle, "SteamService_StartThread");
    g_GetIPCServer = (SteamService_GetIPCServer_t) dlsym(g_service_handle, "SteamService_GetIPCServer");
    g_Stop         = (SteamService_Stop_t)         dlsym(g_service_handle, "SteamService_Stop");
    g_Shutdown     = (SteamService_Shutdown_t)     dlsym(g_service_handle, "SteamService_Shutdown");

    if (!g_StartThread || !g_Stop) {
        LOGE("Symbol resolution failed: StartThread=%p Stop=%p", g_StartThread, g_Stop);
        g_StartThread = nullptr;
        g_GetIPCServer = nullptr;
        g_Stop = nullptr;
        g_Shutdown = nullptr;
        return false;
    }

    LOGI("Symbols resolved: StartThread=%p GetIPCServer=%p Stop=%p Shutdown=%p",
         g_StartThread, g_GetIPCServer, g_Stop, g_Shutdown);
    return true;
}

// ---------------------------------------------------------------------------
// JNI: com.valve.steam.SteamBridge
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_valve_steam_SteamBridge_nativeGetVersion(JNIEnv* env, jclass) {
    char buf[64];
    snprintf(buf, sizeof(buf), "steam_bridge/1.1 NDK=%d ABI=arm64-v8a", __NDK_MAJOR__);
    return env->NewStringUTF(buf);
}

JNIEXPORT jboolean JNICALL
Java_com_valve_steam_SteamBridge_nativeLoadService(JNIEnv* env, jclass) {
    if (g_service_handle) {
        LOGI("steamservice.so already loaded");
        return JNI_TRUE;
    }

    // IMPORTANT: the Valve library is named steamservice.so, not libsteamservice.so.
    // Its SONAME is also steamservice.so.
    g_service_handle = dlopen("steamservice.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_service_handle) {
        LOGE("dlopen(steamservice.so) failed: %s", dlerror());
        return JNI_FALSE;
    }
    LOGI("steamservice.so loaded @ %p", g_service_handle);

    if (!resolve_service_symbols()) {
        dlclose(g_service_handle);
        g_service_handle = nullptr;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_valve_steam_SteamBridge_nativeLoadServiceAt(JNIEnv* env, jclass, jstring jNativeLibDir) {
    if (g_service_handle) {
        LOGI("steamservice.so already loaded");
        return JNI_TRUE;
    }

    const char* nativeLibDir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    std::string base(nativeLibDir ? nativeLibDir : "");
    env->ReleaseStringUTFChars(jNativeLibDir, nativeLibDir);
    if (!base.empty() && base.back() != '/' && base.back() != '\\') base.push_back('/');

    std::string svc = base + "steamservice.so";
    LOGI("Attempting absolute-path load: %s", svc.c_str());

    // Investigation/workaround: force OpenSSL to skip ARM hwcap probing paths.
    // On this device, steamservice.so hits SIGILL in _armv8_sha512_probe during startup.
    setenv("OPENSSL_armcap", "0", 1);
    LOGI("Set OPENSSL_armcap=0");

    g_service_handle = dlopen(svc.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_service_handle) {
        LOGE("dlopen(%s) failed: %s", svc.c_str(), dlerror());
        return JNI_FALSE;
    }
    LOGI("steamservice.so loaded @ %p", g_service_handle);

    if (!resolve_service_symbols()) {
        dlclose(g_service_handle);
        g_service_handle = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_valve_steam_SteamBridge_nativeStartThread(JNIEnv* env, jclass, jstring jDataPath) {
    if (!g_StartThread) {
        LOGE("nativeStartThread called before nativeLoadService");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(jDataPath, nullptr);
    LOGI("SteamService_StartThread(%s)", path);
    bool ok = g_StartThread(path);
    env->ReleaseStringUTFChars(jDataPath, path);
    LOGI("SteamService_StartThread returned %d", (int)ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_valve_steam_SteamBridge_nativeGetIPCServer(JNIEnv* env, jclass) {
    if (!g_GetIPCServer) return 0L;
    void* ptr = g_GetIPCServer();
    return (jlong)(intptr_t)ptr;
}

JNIEXPORT void JNICALL
Java_com_valve_steam_SteamBridge_nativeStop(JNIEnv* env, jclass) {
    if (g_Stop) {
        LOGI("SteamService_Stop()");
        g_Stop();
    }
}

JNIEXPORT void JNICALL
Java_com_valve_steam_SteamBridge_nativeShutdown(JNIEnv* env, jclass) {
    if (g_Shutdown) {
        LOGI("SteamService_Shutdown()");
        g_Shutdown();
    }
    if (g_service_handle) {
        dlclose(g_service_handle);
        g_service_handle = nullptr;
    }
}

} // extern "C"
