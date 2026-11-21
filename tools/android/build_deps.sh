#!/usr/bin/env bash
# tools/android/build_deps.sh -- the Android lane's prebuilt dependencies for arm64-v8a, before the game's configure:
#   <out>/sdl2     SDL2 (the engine's pin, release-2.30.9) as the shared libSDL2.so + its CMake package
#                  (<out>/sdl2/lib/cmake/SDL2 = TE_ANDROID_SDL2_DIR); its Java glue is the same source's android-project
#   <out>/ffmpeg   FFmpeg (tools/android/ffmpeg_android.sh) = TE_ANDROID_FFMPEG_PREFIX
#
#   tools/android/build_deps.sh <SDL2 source dir> <out dir>
#   ANDROID_NDK_HOME (required), ANDROID_API (default 24)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDL_SRC="${1:?usage: build_deps.sh <SDL2 source dir> <out dir>}"
OUT="${2:?usage: build_deps.sh <SDL2 source dir> <out dir>}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must name the NDK root}"
API="${ANDROID_API:-24}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "build_deps: SDL2 (shared) for arm64-v8a, android-$API"
cmake -S "$SDL_SRC" -B "$OUT/sdl2.build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$OUT/sdl2" \
  -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF -DSDL_TESTS=OFF
cmake --build "$OUT/sdl2.build"
cmake --install "$OUT/sdl2.build"
test -f "$OUT/sdl2/lib/libSDL2.so"
ls "$OUT/sdl2/lib/cmake/SDL2"

echo "build_deps: FFmpeg for arm64-v8a"
ANDROID_API="$API" bash "$ROOT/tools/android/ffmpeg_android.sh" "$OUT/ffmpeg"
echo "build_deps: done -> TE_ANDROID_SDL2_DIR=$OUT/sdl2/lib/cmake/SDL2 TE_ANDROID_FFMPEG_PREFIX=$OUT/ffmpeg"
