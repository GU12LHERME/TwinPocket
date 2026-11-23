package com.twinsanityevolved.game;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/**
 * SDL2's Android Activity, in its own process (":game", AndroidManifest.xml): it owns the native window, the EGL surface,
 * the gamepads and the audio device, loads libSDL2.so and libmain.so and calls SDL_main (runner/te_android.cpp). A crash
 * here ends this process only: the launcher, in the app's main process, shows what the run reached.
 */
public final class MainActivity extends SDLActivity {
    static final String EXTRA_SDL_TEST = "com.crashtwinsanity.android.SDL_TEST";
    static final String EXTRA_ISO = "com.crashtwinsanity.android.ISO";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Diag.installCrashHandler(this, "game");
        Diag.log(this, "game", "MainActivity.onCreate (SDL test: " + isSdlTest() + ")");
        super.onCreate(savedInstanceState);
        hideSystemBars();
        Diag.log(this, "game", "MainActivity.onCreate done (SDL libraries " + (mBrokenLibraries ? "NOT loaded" : "loaded") + ")");
    }

    // THE SYSTEM BARS (the RP6, 0.27.0: back from the recents screen the status bar and the navigation handle stayed over
    // the game, and the first picture came at 1920x970 -- the window less the bars -- before the 1920x1080 one, a small
    // picture in a corner then black while the surface was made again). SDL hides the bars only when the game asks for
    // full screen at start and two seconds after the bars show; here they are hidden again before the window comes back
    // (onResume, before SDL's) and whenever it regains focus, so the surface returns at the screen's size. The game is
    // always full screen on Android (Settings.normalize); a swipe from the edge still shows the bars for a moment.
    private void hideSystemBars() {
        Window w = getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        // (the flags SDL itself sets for full screen, so its own checks agree)
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        hideSystemBars();   // before SDL resumes: the surface comes back at the screen's size
        super.onResume();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) hideSystemBars();
        super.onWindowFocusChanged(hasFocus);
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
