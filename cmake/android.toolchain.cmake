# Thin project wrapper around the Android NDK's maintained CMake toolchain.
# Set ANDROID_NDK_HOME to the NDK root before configuring the android-arm64-smoke preset.
if(NOT DEFINED ENV{ANDROID_NDK_HOME} OR "$ENV{ANDROID_NDK_HOME}" STREQUAL "")
    message(FATAL_ERROR "Set ANDROID_NDK_HOME to the Android NDK root")
endif()

set(_te_android_ndk_toolchain "$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
if(NOT EXISTS "${_te_android_ndk_toolchain}")
    message(FATAL_ERROR "Android NDK CMake toolchain not found: ${_te_android_ndk_toolchain}")
endif()
include("${_te_android_ndk_toolchain}")

unset(_te_android_ndk_toolchain)
