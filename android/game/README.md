# Android: the game APK (stage 1 of the Android plan)

This project packages the **real** TwinsanityEvolved code for Android arm64-v8a: `libmain.so` holds the game module
(the decompilation compiled to wasm32 and lowered to C by wasm2c, then compiled by the NDK), the NativeEE engine
(configured for Android: the Linux target folder, the AArch64 fibers, OpenGL ES 3.2) and the runner, entered through
`SDL_main` (`runner/te_android.cpp`). `libSDL2.so` is SDL `release-2.30.9`, the engine's pin.

The native code is built by CMake before Gradle (see `.github/workflows/android-game-arm64.yml`):

```sh
tools/fetch_toolchain.sh                                   # wasi-sdk + wabt (the module's generator, on the build machine)
ANDROID_NDK_HOME=<ndk> tools/android/build_deps.sh <SDL2 source> build-android-deps
cmake --preset android-arm64-game \
  -DTE_ANDROID_SDL2_DIR=$PWD/build-android-deps/sdl2/lib/cmake/SDL2 \
  -DTE_ANDROID_FFMPEG_PREFIX=$PWD/build-android-deps/ffmpeg
cmake --build --preset android-arm64-game
mkdir -p jni/arm64-v8a && cp build-android-arm64-game/libmain.so build-android-deps/sdl2/lib/libSDL2.so jni/arm64-v8a/
gradle -p android/game -Psdl2SourceDir=<SDL2 source> -PnativeLibsDir=$PWD/jni assembleDebug
```

No game data is in the APK or the repository: the game reads your own disc image at run time from the app's folder
(`Android/data/com.twinsanityevolved.game/files/twinsanity.iso`, or the path in `disc-image.txt` there).

Status: stage 1 is the build (every symbol resolved at link time). Running the game on a device -- the disc found, the
module's frames advancing, then the picture (stage 3), the pads (4) and the sound (5) -- is not verified by this build.
The smoke APKs in `android/sdl2-smoke` and `android/nativeee-smoke` stay as they were.
