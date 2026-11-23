# Twinsanity Pocket (Android arm64-v8a)

Twinsanity Pocket is the native Android port of Crash Twinsanity (PS2), built from the TwinsanityEvolved code (the
decompilation compiled to wasm32 and lowered to C by wasm2c, then compiled by the NDK), the NativeEE engine (OpenGL ES
3.2, OpenSL ES, AArch64 fibers) and the runner, entered through `SDL_main` (`runner/te_android.cpp`). `libSDL2.so` is
SDL `release-2.30.9`, the engine's pin. Reference device: Retroid Pocket 6 (Snapdragon 8 Gen 2, Android 13).

The native code is built by CMake before Gradle (see `.github/workflows/android-game-arm64.yml`, which produces
`TwinsanityPocket.apk` in the `twinsanity-pocket-arm64-<run>` artifact):

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

## Identifiers that stay as they are

The port's name changed (TwinsanityEvolved → Crash Twinsanity → Twinsanity Pocket); these did not, so updates install
over the previous version and keep its saves, settings and disc image:

- the application id `com.crashtwinsanity.android` (the app's data folders are named after it);
- the Java package `com.twinsanityevolved.game` (the launcher's component name, which home screens keep for the icon,
  and the JNI symbol `Java_com_twinsanityevolved_game_TeDiag_nativeProbe`);
- the data paths: `files/game/` (ps2r.ini and the memory cards), `files/disc/twinsanity.iso|chd`, `files/logs/`,
  the shared preferences `twinsanity`, and the runner's `<home>/.twinsanityevolved` lookup;
- the signing: the APK is signed by Gradle's debug configuration on the CI runner (the workflow prints the
  certificate's SHA-256 in `apk-signing.log`).

`-PappId=com.twinsanityevolved.game -PappLabel="Twinsanity Pocket (antigo)"` builds the migration APK
(`TwinsanityPocket-migracao-id-antigo.apk`), installed over the first app id to export its saves.

No game data is in the APK or the repository: the player picks their own ISO or CHD with **Selecionar** (Android's
document picker, no storage permission); it is copied into the app's private `files/disc/`.

## The launcher

`LauncherActivity` (Java only, the app's main process) has the tabs Jogar, Gráficos, Controles, Saves, Sistema and
Ferramentas, navigated with a controller (XInput) or touch. **JOGAR** starts `MainActivity` (SDL2, the `:game`
process); Ferramentas has the report, failures, performance, component checks, session history and the ZIP export.
`ProbeActivity` (the `:probe` process) is the diagnostic without the game.

Every activity is fixed in the device's standard landscape (`android:screenOrientation="landscape"`; `MainActivity`
also overrides `SDLActivity.setOrientationBis`): the picture does not rotate with the device, whatever Android's
auto-rotate setting says.

## Logs

In the app's private `files/logs/`:

| file | written by | what |
|---|---|---|
| `stage-game.txt`, `stage-probe.txt` | native (`runner/te_android_diag.cpp`) | the last stage reached, synced: `running`, `crash <signal>`, `exited`, `finished` |
| `latest-<process>.log`, `previous-<process>.log` | native | every stage, the runner's and engine's stdout / stderr (`[twinsanity-pocket]` lines), fatal signals |
| `java-<process>.log`, `java-crash-<process>.txt` | Java (`Diag`) | activity steps, library loading, uncaught exceptions |
| `probe-result.txt` | ProbeActivity | the diagnostic, one line per step |
| `logcat.txt`, `report.txt`, `sessions.tsv` | the launcher | the app's logcat at report time, the combined report, the session history |

logcat tags: `TwinsanityPocket` (native and Java).

The smoke APKs in `android/sdl2-smoke` and `android/nativeee-smoke` are separate development tests, not the port.
