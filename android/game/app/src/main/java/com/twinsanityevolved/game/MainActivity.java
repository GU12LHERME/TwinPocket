package com.twinsanityevolved.game;

import android.os.Bundle;

import org.libsdl.app.SDLActivity;

/**
 * SDL2's Android Activity, in its own process (":game", AndroidManifest.xml): it owns the native window, the EGL surface,
 * the gamepads and the audio device, loads libSDL2.so and libmain.so and calls SDL_main (runner/te_android.cpp). A crash
 * here ends this process only: the launcher, in the app's main process, shows what the run reached.
 */
public final class MainActivity extends SDLActivity {
    static final String EXTRA_SDL_TEST = "com.twinsanityevolved.game.SDL_TEST";
    static final String EXTRA_ISO = "com.twinsanityevolved.game.ISO";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Diag.installCrashHandler(this, "game");
        Diag.log(this, "game", "MainActivity.onCreate (SDL test: " + isSdlTest() + ")");
        super.onCreate(savedInstanceState);
        Diag.log(this, "game", "MainActivity.onCreate done (SDL libraries " + (mBrokenLibraries ? "NOT loaded" : "loaded") + ")");
    }

    private boolean isSdlTest() {
        return getIntent() != null && getIntent().getBooleanExtra(EXTRA_SDL_TEST, false);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "main"};
    }

    @Override
    public void loadLibraries() {
        for (String lib : getLibraries()) {
            Diag.log(this, "game", "loading lib" + lib + ".so");
            try {
                System.loadLibrary(lib);
            } catch (Throwable t) {
                Diag.log(this, "game", "loading lib" + lib + ".so FAILED: " + t);
                throw t;
            }
            Diag.log(this, "game", "lib" + lib + ".so loaded");
        }
    }

    @Override
    protected String[] getArguments() {
        if (isSdlTest()) return new String[] {"--android-sdl-test"};
        // the disc image the launcher checked (runner/te_android.cpp: --te-iso, taken off before the runner's arguments)
        String iso = getIntent() != null ? getIntent().getStringExtra(EXTRA_ISO) : null;
        Diag.log(this, "game", "disc image for the game: " + (iso != null ? iso : "(none from the launcher: the native lookup decides)"));
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (iso != null) { args.add("--te-iso"); args.add(iso); }
        // the launcher's audio options (LauncherActivity "Áudio"), as the environment the engine and SDL read
        android.content.SharedPreferences prefs = getSharedPreferences("twinsanity", MODE_PRIVATE);
        int buffer = prefs.getInt("audio_buffer", 2048);
        String driver = prefs.getString("audio_driver", "auto");
        args.add("--te-env"); args.add("TE_AUDIO_BUFFER_FRAMES=" + buffer);
        if (!"auto".equals(driver)) { args.add("--te-env"); args.add("SDL_AUDIODRIVER=" + driver); }
        Diag.log(this, "game", "audio options: buffer " + buffer + " frames, driver " + driver);
        return args.toArray(new String[0]);
    }

    @Override
    protected void onDestroy() {
        Diag.log(this, "game", "MainActivity.onDestroy (finishing " + isFinishing() + ")");
        super.onDestroy();
        if (isFinishing()) {
            // a fresh process for the next start: the engine's and SDL's native state are not made to start twice
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
