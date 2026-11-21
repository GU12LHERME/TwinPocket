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

## Diagnostic build

The launcher icon opens **LauncherActivity** (Java only, no native code). It shows the disc image check and what the
last runs reached, and has these buttons:

- **Selecionar ISO** -- Android's document picker (`ACTION_OPEN_DOCUMENT`, no storage permission): the chosen file is
  copied through the ContentResolver into the app's private `files/disc/twinsanity.iso` (progress shown, free space
  checked, size and ISO 9660 / SYSTEM.CNF verified), and the read grant is kept. The game gets that path (`--te-iso`).
  A file another app put in `Android/data/<package>/files` can be unreadable to this app (EACCES on Android 11+);
  the picker avoids that.
- **Iniciar jogo** -- the game (MainActivity, SDL2, in the `:game` process). Without a usable disc image it explains
  where to put it instead of starting.
- **Diagnóstico (sem o jogo)** -- ProbeActivity in the `:probe` process: lists the installed native libraries, loads
  `libSDL2.so` and `libmain.so`, then runs `TeDiag.nativeProbe` (engine fibers, storage, disc image, an OpenGL ES 3.2
  context and the engine's shaders as shipped and as `#version 320 es`). No game, no SDL window.
- **Teste SDL (sem o jogo)** -- MainActivity with `--android-sdl-test`: SDL window, GLES renderer, gamepad events for
  10 s (B / Back ends it). No engine, no game.
- **Compartilhar relatório** / **Copiar relatório** -- one text file (`files/logs/report.txt`) with the status, every
  log below and this app's own logcat, through the share sheet (LogProvider) or the clipboard.

Logs (in `/data/data/com.twinsanityevolved.game/files/logs/`, private to the app):

| file | written by | what |
|---|---|---|
| `stage-game.txt`, `stage-probe.txt` | native (`runner/te_android_diag.cpp`) | the last stage reached, synced: `running`, `crash <signal>`, `exited`, `finished` |
| `latest-<process>.log`, `previous-<process>.log` | native | every stage, the runner's and engine's stdout / stderr, fatal signals |
| `java-<process>.log`, `java-crash-<process>.txt` | Java (`Diag`) | activity steps, library loading, uncaught exceptions |
| `probe-result.txt` | ProbeActivity | the diagnostic, one line per step (written before each step runs) |
| `logcat.txt`, `report.txt` | the launcher | this app's logcat at the time of the report; the combined report |

Every line also goes to logcat with the tag `TwinsanityEvolved`.
