# Android ARM64 bootstrap baseline

This note records the repository and tool availability checked for the first
Android build step. The Android preset is intentionally a toolchain smoke build,
not a game build or APK: `TE_ANDROID_SMOKE_ONLY` returns from the root CMake file
before the wasm game, NativeEE, SDL2, FFmpeg, or renderer are configured.

## Existing build lanes

- Windows: `windows-clang-cl-release`, `windows-clang-cl-dev`, and
  `windows-clang-cl-release-lto` in `CMakePresets.json`.
- PS Vita: `vita-release` and `vita-module-release` in `CMakePresets.json`.
- macOS and Linux: the README documents native CMake builds; this checkout has
  no named configure presets for those lanes.
- Desktop game builds require the pinned WASI SDK and WABT tools under
  `tools/toolchain/`.

## Android smoke build

The `android-arm64-smoke` preset uses `cmake/android.toolchain.cmake`, which
includes the official NDK CMake toolchain. Set `ANDROID_NDK_HOME` to the NDK root.
The preset selects ABI `arm64-v8a`, API level 24, and a separate
`build-android-arm64-smoke/` directory. It only builds
`te_android_ndk_smoke`; the post-build check verifies the artifact is a
little-endian ELF64 file for AArch64. It is not executable as a normal desktop
program and is not an APK.

Expected commands on a machine with CMake, Ninja, and the NDK installed:

```sh
cmake --preset android-arm64-smoke
cmake --build --preset android-arm64-smoke
```

## Environment observed for this baseline

At the time this baseline was recorded, the current workspace environment did
not expose `cmake`, `ninja`, or `git` on `PATH`; no Android NDK environment
variable or SDK/NDK directory was found in the checked standard locations. The
pinned WASI SDK and WABT executables were also absent from `tools/toolchain/`.
Therefore the pre-change desktop/Vita builds and the Android cross-compile could
not be configured or run in this environment. This is an environment
limitation, not a passing build result.

## Stage 1: the game, the engine and the runner for Android arm64-v8a

Branch `android-game-arm64`, workflow `.github/workflows/android-game-arm64.yml`, preset `android-arm64-game`
(`TE_ANDROID=ON`). The smoke builds above are unchanged.

- **Game module:** the decompilation is compiled to wasm32 by the pinned wasi-sdk and lowered to C by wasm2c on the build
  machine, exactly as for the desktop; the NDK's clang compiles that C for AArch64.
- **Engine:** configured by the same NDK toolchain (ABI, API, STL, SDL2 and FFmpeg forwarded to its ExternalProject).
  `engine/platform` builds the Linux target folder for Android (bionic is POSIX) with
  `posix/fiber_android_arm64.cpp` (bionic has no `makecontext` / `swapcontext`; tested under qemu-aarch64 in CI) and the
  GL header `android/gl_android.h`, which maps the GL 4.1 spellings the backend uses onto OpenGL ES 3.2. The SDL context
  asks for ES 3.2. Crash backtraces use the unwinder below API 33.
- **Runner:** linked as `libmain.so` with `-Wl,--no-undefined` (a missing body is a link error); `runner/te_android.cpp`
  is `SDL_main`: stdout/stderr to logcat, HOME and the working directory in the app's external files folder, `--iso`
  from `twinsanity.iso` / `disc-image.txt` / the first `*.iso` there. Save-state relaunch is the refusing arm, as on the Vita.
- **Dependencies:** `tools/android/build_deps.sh` builds SDL2 `release-2.30.9` (shared `libSDL2.so`) and FFmpeg 6.1.2
  (`tools/android/ffmpeg_android.sh`: static, LGPL, MPEG-1/2 decoders + swscale only) for arm64-v8a.
- **APK:** `android/game` packages `libmain.so` + `libSDL2.so` with SDL2's Java glue (`com.twinsanityevolved.game`).

CI checks: ELF64 AArch64, the NEEDED libraries (libSDL2, libGLESv3, libEGL, liblog), the components' symbols in
`libmain.so` (`SDL_main`, the runner, ~17k `w2c_game_*` functions of the module, the wasm2c runtime, the seam's `ps2e_*`
bodies, the GL ES backend, the AArch64 fibers, input, audio, the disc file system, sound, GS, the title HLE, FFmpeg's
MPEG-2 decoder), the APK carrying those exact files, and `tools/ci/asset_scan.py` over the tree and the APK's native code.

**Not verified by stage 1:** anything at run time. The build proves the code compiles and links for Android; it does not
prove the game boots. Next (stage 2): run on a device with the user's own disc image and read logcat -- the disc
recognised, the module's frames advancing -- then the shaders as GLSL ES (stage 3: the GL 4.1 `#version 410 core`
sources need an ES translation, as `VitaGlsl` does for the Vita), the pads (4) and the sound and movies (5).
