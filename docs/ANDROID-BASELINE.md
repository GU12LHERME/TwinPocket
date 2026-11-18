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
