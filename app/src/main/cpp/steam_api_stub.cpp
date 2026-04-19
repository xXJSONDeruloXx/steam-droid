#include <android/log.h>

#define TAG "SteamAPIStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" {

__attribute__((constructor)) static void on_load() {
    LOGI("libsteam_api.so loaded");
}

void* CreateInterface(const char* name, int* returnCode) {
    LOGI("CreateInterface(%s)", name ? name : "<null>");
    if (returnCode) *returnCode = 0;
    return nullptr;
}

bool SteamAPI_Init() {
    LOGI("SteamAPI_Init()");
    return false;
}

bool SteamAPI_InitSafe() {
    LOGI("SteamAPI_InitSafe()");
    return false;
}

void SteamAPI_Shutdown() {
    LOGI("SteamAPI_Shutdown()");
}

bool SteamAPI_IsSteamRunning() {
    LOGI("SteamAPI_IsSteamRunning()");
    return false;
}

}
