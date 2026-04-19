cmake_minimum_required(VERSION 3.22.1)
project("steam_bridge")

# steam_bridge.so: thin JNI wrapper that dlopen's steamservice.so
# and calls its C-style exports from Java/Kotlin.
add_library(steam_bridge SHARED steam_bridge.cpp)

target_link_libraries(steam_bridge
    android
    log
    dl)

# Investigation stubs: if steamservice.so tries to dlopen these at runtime,
# their constructors/logging will tell us in logcat.
add_library(steam_api SHARED steam_api_stub.cpp)
target_link_libraries(steam_api android log)

add_library(crashhandler SHARED crashhandler_stub.cpp)
set_target_properties(crashhandler PROPERTIES PREFIX "")
target_link_libraries(crashhandler android log)
