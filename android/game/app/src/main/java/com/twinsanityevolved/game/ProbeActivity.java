package com.twinsanityevolved.game;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * The diagnostic mode (the launcher's "Diagnóstico" button), in its own process (":probe"): loads libSDL2.so and
 * libmain.so the way SDLActivity does, then runs the native probe (TeDiag.nativeProbe: the engine's fibers, storage, the
 * disc image, an OpenGL ES 3.2 context and the engine's shaders) -- without starting the game or an SDL window. Each step
 * is appended to files/logs/probe-result.txt before it runs, so a step that kills the process is the file's last line.
 */
public final class ProbeActivity extends Activity {
    private TextView text;
    private final StringBuilder out = new StringBuilder();
    private File result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.installCrashHandler(this, "probe");
        result = new File(Diag.logsDir(this), "probe-result.txt");
        Diag.write(result, "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        Button back = new Button(this);
        back.setText("Voltar");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        back.requestFocus();

        new Thread(this::run, "te-probe").start();
    }

    private void step(String line) {
        Diag.log(this, "probe", line);
        synchronized (out) {
            out.append(line).append('\n');
            Diag.write(result, out.toString());
        }
        final String shown = out.toString();
        runOnUiThread(() -> text.setText(shown));
    }

    private void run() {
        step("== Twinsanity Pocket diagnostic (no game) -- " + Diag.now());
        step("device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE
                + " API " + Build.VERSION.SDK_INT + ", ABIs " + android.text.TextUtils.join(",", Build.SUPPORTED_ABIS));
        String libDir = getApplicationInfo().nativeLibraryDir;
        step("native library folder: " + libDir);
        File[] libs = new File(libDir).listFiles();
        if (libs == null || libs.length == 0) {
            step("  (EMPTY or unreadable: the APK's native libraries were not installed for this device's ABI)");
        } else {
            for (File f : libs) step("  " + f.getName() + "  " + f.length() + " bytes");
        }
        File ext = getExternalFilesDir(null);
        step("app folder for the disc image: " + (ext != null ? ext.getAbsolutePath() : "(not available)"));

        step("loading libSDL2.so ...");
        try {
            System.loadLibrary("SDL2");
            step("libSDL2.so: loaded");
        } catch (Throwable t) {
            step("libSDL2.so: FAILED -- " + t);
            step("== stopped: libmain.so needs libSDL2.so");
            return;
        }
        step("loading libmain.so (the game, the engine and the runner; its static initialisation runs now) ...");
        try {
            System.loadLibrary("main");
            step("libmain.so: loaded");
        } catch (Throwable t) {
            step("libmain.so: FAILED -- " + t);
            return;
        }
        step("running the native probe (fibers, storage, disc image, OpenGL ES 3.2, the engine's shaders) ...");
        try {
            String r = TeDiag.nativeProbe(getFilesDir().getAbsolutePath(), ext != null ? ext.getAbsolutePath() : "");
            for (String line : r.split("\n")) step("  " + line);
        } catch (Throwable t) {
            step("native probe: FAILED -- " + t);
        }
        step("== diagnostic finished -- " + Diag.now());
    }
}
