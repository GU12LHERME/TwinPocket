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
        // the settings (audio driver and buffer, graphics, controls) live in ps2r.ini (Settings.java), which
        // runner/te_android.cpp reads before SDL starts: nothing to pass here
        android.content.SharedPreferences prefs = getSharedPreferences("twinsanity", MODE_PRIVATE);
        Settings settings = new Settings(this);
        Diag.log(this, "game", "settings file " + settings.file() + ": audio driver "
                + settings.get(Settings.AUDIO_DRIVER, "openslES (default)") + ", buffer " + settings.get(Settings.AUDIO_BUFFER, "2048 (default)"));
        if (prefs.getBoolean("diagnostic_mode", false)) {
            for (String e : LauncherActivity.DIAGNOSTIC_ENV) { args.add("--te-env"); args.add(e); }
            Diag.log(this, "game", "diagnostic mode on");
        }
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
