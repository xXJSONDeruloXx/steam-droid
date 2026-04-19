/*
 * steam_bridge.cpp
 *
 * Thin JNI wrapper that dlopen's steamservice.so (which exports plain C
 * functions, not JNI-named functions) and bridges them to Kotlin/Java.
 *
 * Chunk 1 test target:
 *   - nativeLoadService()  -> dlopen succeeds, returns true
 *   - nativeStartThread()  -> SteamService_StartThread called, returns bool
 *   - nativeStop()         -> SteamService_Stop called
 *   - nativeGetVersion()   -> returns build string for smoke-test
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <cstring>

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
// Load a library by soname or absolute path
// ---------------------------------------------------------------------------
static bool load_dep(const char* name) {
    void* h = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        LOGE("dlopen(%s) failed: %s", name, dlerror());
        return false;
    }
    LOGI("dlopen(%s) OK @ %p", name, h);
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

    // These are the actual Valve Android libs staged into jniLibs/arm64-v8a.
    // readelf verification shows they only depend on Android system libs and each other.
    const char* deps[] = {
        "libtier0_s.so",
        "libvstdlib_s.so",
        "libsteamnetworkingsockets.so",
        "libsteamclient.so",
        nullptr
    };
    for (int i = 0; deps[i]; i++) {
        bool ok = load_dep(deps[i]);
        if (!ok) {
            LOGE("Required dependency %s failed to load", deps[i]);
            return JNI_FALSE;
        }
    }

    // IMPORTANT: the Valve library is named steamservice.so, not libsteamservice.so.
    // Its SONAME is also steamservice.so.
    g_service_handle = dlopen("steamservice.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_service_handle) {
        LOGE("dlopen(steamservice.so) failed: %s", dlerror());
        return JNI_FALSE;
    }
    LOGI("steamservice.so loaded @ %p", g_service_handle);

    // Resolve symbols
    g_StartThread  = (SteamService_StartThread_t)  dlsym(g_service_handle, "SteamService_StartThread");
    g_GetIPCServer = (SteamService_GetIPCServer_t) dlsym(g_service_handle, "SteamService_GetIPCServer");
    g_Stop         = (SteamService_Stop_t)         dlsym(g_service_handle, "SteamService_Stop");
    g_Shutdown     = (SteamService_Shutdown_t)     dlsym(g_service_handle, "SteamService_Shutdown");

    if (!g_StartThread || !g_Stop) {
        LOGE("Symbol resolution failed: StartThread=%p Stop=%p", g_StartThread, g_Stop);
        dlclose(g_service_handle);
        g_service_handle = nullptr;
        return JNI_FALSE;
    }

    LOGI("Symbols resolved: StartThread=%p GetIPCServer=%p Stop=%p Shutdown=%p",
         g_StartThread, g_GetIPCServer, g_Stop, g_Shutdown);
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

    std::string dep1 = base + "libtier0_s.so";
    std::string dep2 = base + "libvstdlib_s.so";
    std::string dep3 = base + "libsteamnetworkingsockets.so";
    std::string dep4 = base + "libsteamclient.so";
    std::string svc  = base + "steamservice.so";

    if (!load_dep(dep1.c_str())) return JNI_FALSE;
    if (!load_dep(dep2.c_str())) return JNI_FALSE;
    if (!load_dep(dep3.c_str())) return JNI_FALSE;
    if (!load_dep(dep4.c_str())) return JNI_FALSE;

    g_service_handle = dlopen(svc.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_service_handle) {
        LOGE("dlopen(%s) failed: %s", svc.c_str(), dlerror());
        return JNI_FALSE;
    }

    g_StartThread  = (SteamService_StartThread_t)  dlsym(g_service_handle, "SteamService_StartThread");
    g_GetIPCServer = (SteamService_GetIPCServer_t) dlsym(g_service_handle, "SteamService_GetIPCServer");
    g_Stop         = (SteamService_Stop_t)         dlsym(g_service_handle, "SteamService_Stop");
    g_Shutdown     = (SteamService_Shutdown_t)     dlsym(g_service_handle, "SteamService_Shutdown");

    if (!g_StartThread || !g_Stop) {
        LOGE("Symbol resolution failed after absolute-path load");
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
