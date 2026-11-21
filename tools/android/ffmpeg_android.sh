#!/usr/bin/env bash
# tools/android/ffmpeg_android.sh -- the Android lane's FFmpeg: the engine's static LGPL recipe (engine/tools/ci/ffmpeg_static.sh:
# the mpeg1/mpeg2 video decoders, the mpegvideo parser and swscale, nothing else -- what the movies' decoder
# engine/engine/ipu/hle/ffmpeg_video_decoder.cpp calls) cross-compiled for Android arm64-v8a with the NDK's clang.
# The same source pin as tools/vita/ffmpeg_vita.sh (the release tarball of the recipe's tag, checked by sha256).
#
#   tools/android/ffmpeg_android.sh <prefix>     build into <prefix> (cached when <prefix>/PS2R_FFMPEG_CONFIGURE matches)
#   ANDROID_NDK_HOME                             the NDK root (required)
#   ANDROID_API                                  the minimum API level (default 24)
#   FFMPEG_SOURCE=<file>                         a local copy of the pinned tarball instead of the download
#   FFMPEG_JOBS=<n>                              make jobs (default: the CPU count)
#
# Licence: LGPL v2.1 or later, as the recipe (--disable-gpl --disable-nonfree --disable-version3), checked in the built
# archive's embedded configuration string after the build, with the MPEG-2 decoder and the mpegvideo parser.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RECIPE="$ROOT/engine/tools/ci/ffmpeg_static.sh"
VERSION=6.1.2
TAG=n6.1.2
URL="https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"
SHA256=3b624649725ecdc565c903ca6643d41f33bd49239922e45c9b1442c63dca4e38
API="${ANDROID_API:-24}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must name the NDK root}"

RECIPE_TAG="$(env -u FFMPEG_TAG bash "$RECIPE" --print-configure | awk 'NR == 1 {print $2}')"
if [ "$RECIPE_TAG" != "$TAG" ]; then
  echo "ffmpeg_android: the engine recipe builds FFmpeg $RECIPE_TAG; this script pins $TAG -- update VERSION / TAG / SHA256" >&2
  exit 1
fi
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) HOST_TAG=linux-x86_64 ;;
  Darwin/*)     HOST_TAG=darwin-x86_64 ;;
  *) echo "ffmpeg_android: unsupported build host $(uname -s)/$(uname -m)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TC/bin/aarch64-linux-android$API-clang"
[ -x "$CC" ] || { echo "ffmpeg_android: $CC not found (ANDROID_NDK_HOME / ANDROID_API)" >&2; exit 1; }

ARGS=()
while IFS= read -r a; do ARGS+=("$a"); done < <(env -u FFMPEG_TAG bash "$RECIPE" --print-args)
ARGS+=(
  --enable-cross-compile --target-os=android --arch=aarch64 --cpu=armv8-a
  "--cc=$CC" "--cxx=$CC++" "--ld=$CC" "--ar=$TC/bin/llvm-ar" "--nm=$TC/bin/llvm-nm" "--ranlib=$TC/bin/llvm-ranlib"
  "--strip=$TC/bin/llvm-strip" "--sysroot=$TC/sysroot"
  --enable-neon --disable-runtime-cpudetect
)

PREFIX="${1:?usage: ffmpeg_android.sh <prefix>}"
case "$PREFIX" in /*) ;; *) PREFIX="$(pwd)/$PREFIX" ;; esac
JOBS="${FFMPEG_JOBS:-$(nproc 2>/dev/null || echo 4)}"
RECORD="$({ echo "version=$VERSION"; echo "sha256=$SHA256"; echo "api=$API"; printf '%s\n' "${ARGS[@]}"; })"
if [ -f "$PREFIX/lib/libavcodec.a" ] && [ -f "$PREFIX/PS2R_FFMPEG_CONFIGURE" ] && [ "$(cat "$PREFIX/PS2R_FFMPEG_CONFIGURE")" = "$RECORD" ]; then
  echo "ffmpeg_android: cached at $PREFIX (FFmpeg $VERSION)"
  exit 0
fi

sha256_of() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'; else sha256sum "$1" | awk '{print $1}'; fi; }
if [ -n "${FFMPEG_SOURCE:-}" ]; then
  TARBALL="$FFMPEG_SOURCE"
else
  TARBALL="$PREFIX.download/ffmpeg-$VERSION.tar.xz"
  if [ ! -f "$TARBALL" ] || [ "$(sha256_of "$TARBALL")" != "$SHA256" ]; then
    mkdir -p "$PREFIX.download"
    echo "ffmpeg_android: downloading $URL"
    curl -fsSL --retry 3 -o "$TARBALL.part" "$URL"
    mv "$TARBALL.part" "$TARBALL"
  fi
fi
got="$(sha256_of "$TARBALL")"
[ "$got" = "$SHA256" ] || { echo "ffmpeg_android: $TARBALL has sha256 $got, not the pinned $SHA256" >&2; exit 1; }

SRC="$PREFIX.src"; BLD="$PREFIX.build"
rm -rf "$SRC" "$BLD" "$PREFIX"
mkdir -p "$SRC" "$BLD"
tar -xJf "$TARBALL" -C "$SRC" --strip-components=1
echo "ffmpeg_android: configure (FFmpeg $VERSION, aarch64-linux-android$API, static, LGPL)"
(cd "$BLD" && "$SRC/configure" --prefix="$PREFIX" "${ARGS[@]}") > "$BLD/configure.log" 2>&1 ||
  { tail -n 40 "$BLD/configure.log" >&2; echo "ffmpeg_android: configure FAILED" >&2; exit 1; }
make -C "$BLD" -j"$JOBS" > "$BLD/make.log" 2>&1 || { tail -n 40 "$BLD/make.log" >&2; echo "ffmpeg_android: make FAILED" >&2; exit 1; }
make -C "$BLD" install > "$BLD/install.log" 2>&1 || { tail -n 20 "$BLD/install.log" >&2; exit 1; }

CONF="$(strings "$PREFIX/lib/libavcodec.a" | grep -- '--prefix=' | sort -u || true)"
[ "$(grep -c . <<< "$CONF" || true)" -eq 1 ] || { echo "ffmpeg_android: expected exactly one embedded configuration string" >&2; exit 1; }
if grep -qE -- '--enable-gpl|--enable-nonfree|--enable-version3' <<< "$CONF"; then echo "ffmpeg_android: not LGPL v2.1: $CONF" >&2; exit 1; fi
SYMS="$("$TC/bin/llvm-nm" -g --defined-only "$PREFIX/lib/libavcodec.a" 2>/dev/null || true)"
for sym in ff_mpeg2video_decoder ff_mpeg1video_decoder ff_mpegvideo_parser; do
  grep -q " $sym\$" <<< "$SYMS" || { echo "ffmpeg_android: $sym is not in libavcodec.a" >&2; exit 1; }
done
mkdir -p "$PREFIX/share/licenses"
cp "$SRC/COPYING.LGPLv2.1" "$PREFIX/share/licenses/FFmpeg-COPYING.LGPLv2.1.txt"
cp "$SRC/LICENSE.md" "$PREFIX/share/licenses/FFmpeg-LICENSE.md"
printf '%s\n' "$CONF" > "$PREFIX/share/licenses/FFmpeg-BUILD-CONFIGURATION.txt"
rm -rf "$SRC" "$BLD"
printf '%s\n' "$RECORD" > "$PREFIX/PS2R_FFMPEG_CONFIGURE"
echo "ffmpeg_android: installed FFmpeg $VERSION -> $PREFIX ($(cd "$PREFIX/lib" && ls *.a | tr '\n' ' '))"
