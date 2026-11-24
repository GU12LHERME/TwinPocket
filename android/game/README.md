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
gradle -p android/game -Psdl2SourceDir=<SDL2 source> -PnativeLibsDir=$PWD/jni assembleRelease   # (TP_KEYSTORE: the fixed key)
```

## Identity, signing and updates

Since 1.5.0 the application id is `com.twinsanitypocket.android` and every APK is signed with one fixed key, so each new
version installs over the previous one and keeps its saves, settings and disc image. The earlier ids
(`com.twinsanityevolved.game`, then `com.crashtwinsanity.android`) were signed by a throw-away key per CI run; to Android
they are other apps: their saves come over with Saves → Exportar in the old app and Saves → Importar in this one.

The key lives only in the repository's Actions secrets `TP_KEYSTORE_B64` (the PKCS12 keystore, base64) and
`TP_KEYSTORE_PASSWORD` (alias `twinsanitypocket`); `build.gradle` signs with it when `TP_KEYSTORE` names the file, and the
workflow refuses an APK whose certificate is not SHA-256 `0b8284df…21820fac`. A local build without the key is signed with
Gradle's debug key (it will not install over a CI build). Since 1.8.0 CI ships the release build type: not debuggable,
not shrunk (R8 off: SDL's Java side is reached by name through JNI), libmain.so's symbols kept. Losing the key means the next version cannot update the
installed one: keep the owner's backup of the keystore.

What else stays as it was:

- the Java package `com.twinsanityevolved.game` (the launcher's component name, which home screens keep for the icon,
  and the JNI symbol `Java_com_twinsanityevolved_game_TeDiag_nativeProbe`);
- the data paths: `files/game/` (ps2r.ini and the memory cards), `files/disc/twinsanity.iso|chd`, `files/logs/`,
  the shared preferences `twinsanity`, and the runner's `<home>/.twinsanityevolved` lookup;

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

## The performance report (Ferramentas → Desempenho, the report, `sessions.tsv`)

Every ~10 s of play (600 presents) the game writes one census window; the launcher reads it after the game, never while it
plays. The lines (all in `latest-game.log`):

| line | from | what |
|---|---|---|
| `[te.gfx] pace` | `graphics.cpp` NotePace | the window's fps, longest gap, the frame's split (submit / present / the rest) |
| `[te.android] perf` | `graphics.cpp` | the process's CPU (one core / all cores) and its peak memory |
| `[te.android] frames` | `graphics.cpp` FrameTimes | frame time avg / min / max, p50 / p95 / p99, jitter (standard deviation), gaps over 1.5x and 2.5x the median -- a 0.1 ms histogram, one increment a present |
| `[te.gfx] gpu frame` | `gl_gpu_timers.cpp` GlFrameGpuTime | the GPU's time of a frame: one GL_TIME_ELAPSED query around a whole frame, 1 frame in 4, read back later (never waited for); disjoint results dropped; "unavailable" once without EXT_disjoint_timer_query. `TE_GFX_FRAME_GPU=0` off, `=N` 1 in N |
| `[te.android] config` | `display.cpp` PerfConfig | the graphic settings the window was measured with (preset, scale, anisotropy, draw distance, aspect, frame rate, vsync) |
| `[te.android] hitch` | `graphics.cpp` NoteHitch | a frame over 50 ms (`TE_GFX_HITCH_MS`): the game thread's work on its picture, the drawing thread's submission and swap, the wait for geometry builds, shaders compiled / programs linked and their time, textures and meshes made, its boundary (matched with the `area:` lines) and whether a loading picture was up; at most 400 a session |
| `[te.android] display` | `MainActivity` | the panel's rate and modes (the rate is the system's: 1.6.0's 60 Hz request made the frames less even and was taken out) |
| `[twinsanity-pocket] tick` | `time.cpp` (`TE_TICK_LOG_MS=50`, set by the Android runner) | a game-thread tick over 50 ms: its phases, the port's load work in it (`te_load_census.h`) and the sections sampled in it (`TE_PROF=1`, every 2 ms: "(own code)" = the game's code); the hitch list uses it to tell the port's work from the game's |
| `[te.android] power` | `Telemetry.java` (game process, every 10 s, through `TeDiag.nativeLog`) | battery temperature, current (mA), voltage, estimated power (W, on the battery only), power source, level, thermal status (API 29) and headroom (API 30), the display's refresh rate; `na` where the device does not tell |

The summary shows minimum / average / maximum where it applies. `sessions.tsv` keeps the original 16 columns (older lines
stay readable) and adds: preset, anisotropy, p95 / p99 frame ms, jitter, late frames, GPU avg / max ms, battery temperature
avg / max, average power, worst thermal status, refresh rate and the session's main settings; since 1.6.0 the hitches
outside loading pictures and the worst one. Desempenho lists each hitch with its likeliest cause (shaders, geometry,
rendering, the game thread -- after an area change or not -- or a loading picture), from the frame's own figures.

The smoke APKs in `android/sdl2-smoke` and `android/nativeee-smoke` are separate development tests, not the port.
