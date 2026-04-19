cmake_minimum_required(VERSION 3.22.1)
project("steam_bridge")

# steam_bridge.so: thin JNI wrapper that dlopen's steamservice.so
# and calls its C-style exports from Java/Kotlin.
add_library(steam_bridge SHARED steam_bridge.cpp)

target_link_libraries(steam_bridge
    android
    log
    dl)
