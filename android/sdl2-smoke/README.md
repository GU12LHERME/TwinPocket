# Android SDL2 smoke app

This separate Android project packages the repository's existing SDL2 pin (`release-2.30.9`) into a small ARM64 APK. It is a platform integration check only: the app draws a simple SDL screen, shows controller directions and face buttons, and contains no game code, Crash Twinsanity assets, NativeEE, FFmpeg, or the project's renderer.

SDL2's Android `SDLActivity` Java glue is compiled from the same pinned SDL source tree used to build `libSDL2.so`; the app activity subclasses that upstream class. SDL's zlib license is already included at `engine/tools/dist/licenses/SDL2-LICENSE.txt`.

## Build

Requirements: JDK 17, Android SDK with platform 35, Android NDK `30.0.16248370`, CMake `3.31.6`, and Gradle `8.9`.

```sh
git clone --branch release-2.30.9 --depth 1 https://github.com/libsdl-org/SDL.git /tmp/SDL2
gradle --no-daemon -Psdl2SourceDir=/tmp/SDL2 -p android/sdl2-smoke assembleDebug
```

The APK is written to `android/sdl2-smoke/app/build/outputs/apk/debug/app-debug.apk`. The matching GitHub Actions workflow builds the same `arm64-v8a` APK and uploads it with build logs.

The screen lights its D-pad and A/B/X/Y indicators for a mapped SDL game controller; a raw joystick fallback reads axes, hats, and the first four buttons. Arrows/WASD and Z/X are keyboard fallbacks. Android background/foreground events pause and resume drawing; SDL quit/termination events and Escape close the native loop.
