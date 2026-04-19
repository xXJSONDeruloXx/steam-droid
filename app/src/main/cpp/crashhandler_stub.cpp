#include <android/log.h>

#define TAG "CrashHandlerStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct CrashHandlerHandler {
    virtual ~CrashHandlerHandler() {
        LOGI("~CrashHandlerHandler()");
    }

    virtual void* Slot1() { LOGI("Handler::Slot1() -> self"); return this; }
    virtual void* Slot2() { LOGI("Handler::Slot2() -> self"); return this; }
    virtual void* Slot3() { LOGI("Handler::Slot3() -> self"); return this; }
    virtual void* Slot4() { LOGI("Handler::Slot4() -> self"); return this; }
    virtual void* Slot5() { LOGI("Handler::Slot5() -> self"); return this; }
    virtual void* Slot6() { LOGI("Handler::Slot6() -> self"); return this; }
    virtual void* Slot7() { LOGI("Handler::Slot7() -> self"); return this; }
    virtual void* Slot8() { LOGI("Handler::Slot8() -> self"); return this; }
    virtual void* Slot9() { LOGI("Handler::Slot9() -> self"); return this; }
    virtual void* Slot10() { LOGI("Handler::Slot10() -> self"); return this; }
};

struct CrashHandlerFactory {
    virtual ~CrashHandlerFactory() {
        LOGI("~CrashHandlerFactory()");
    }

    virtual void* Slot1() { LOGI("Factory::Slot1() -> self"); return this; }
    virtual void* Slot2() { LOGI("Factory::Slot2() -> self"); return this; }
    virtual void* Slot3() { LOGI("Factory::Slot3() -> self"); return this; }
    virtual void* Slot4() { LOGI("Factory::Slot4() -> self"); return this; }
    virtual void* Slot5() { LOGI("Factory::Slot5() -> self"); return this; }
    virtual void* Slot6() {
        LOGI("Factory::Slot6() -> new handler");
        return new CrashHandlerHandler();
    }
    virtual void* Slot7() { LOGI("Factory::Slot7() -> self"); return this; }
    virtual void* Slot8() { LOGI("Factory::Slot8() -> self"); return this; }
    virtual void* Slot9() { LOGI("Factory::Slot9() -> self"); return this; }
    virtual void* Slot10() { LOGI("Factory::Slot10() -> self"); return this; }
};

extern "C" {

__attribute__((constructor)) static void on_load() {
    LOGI("crashhandler.so loaded");
}

void* CreateInterface(const char* name, int* returnCode) {
    LOGI("CreateInterface(%s) -> new factory", name ? name : "<null>");
    if (returnCode) *returnCode = 0;
    return new CrashHandlerFactory();
}

}
