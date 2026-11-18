# Android NativeEE smoke APK

This separate Android project reuses the verified SDL2 `release-2.30.9` application shell and links the real `ps2r_core` plus the NativeEE Android platform bootstrap. On launch it checks platform initialization, monotonic time, NativeEE file logging, thread creation/join, and private file writing/reading. Each result is shown as PASS or FAIL. SDL controller input and pause/resume/termination handling are retained.

The app obtains its preference directory through SDL and passes that app-specific directory to NativeEE. This APK contains no Crash Twinsanity files or game code. Fibers, game audio, titles, and the full renderer remain outside this phase.

SDL2's Android Java glue and `libSDL2.so` come from the project's pinned SDL release. SDL's zlib license is included at `engine/tools/dist/licenses/SDL2-LICENSE.txt`.

## Build

Requirements: JDK 17, Android SDK platform 35, Android NDK `30.0.16248370`, CMake `3.31.6`, Gradle `8.9`, and SDL `release-2.30.9`.

```sh
git clone --branch release-2.30.9 --depth 1 https://github.com/libsdl-org/SDL.git /tmp/SDL2
gradle --no-daemon -Psdl2SourceDir=/tmp/SDL2 -p android/nativeee-smoke assembleDebug
```

The GitHub Actions workflow builds the `arm64-v8a` APK, checks its package and ELF headers, and publishes the APK and logs as an artifact.
