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
        return isSdlTest() ? new String[] {"--android-sdl-test"} : new String[0];
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
