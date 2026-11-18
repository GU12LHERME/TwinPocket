cmake_minimum_required(VERSION 3.24)
project(TwinsanitySDL2Smoke LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The SDL2 smoke APK requires the Android NDK arm64-v8a target")
endif()

if(NOT DEFINED SDL2_SOURCE_DIR OR NOT EXISTS "${SDL2_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "SDL2_SOURCE_DIR must point to the pinned SDL release-2.30.9 source tree")
endif()

# Match the repository's existing engine/cmake/Sdl2Dependency.cmake pin. Keep
# SDL as a shared Android library so SDLActivity's stock library list loads SDL2
# before the app's libmain.so.
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${SDL2_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/SDL2" EXCLUDE_FROM_ALL)

add_library(main SHARED main.cpp)
target_compile_features(main PRIVATE cxx_std_17)
target_compile_options(main PRIVATE -Wall -Wextra -Werror)
target_link_libraries(main PRIVATE SDL2 SDL2main)
