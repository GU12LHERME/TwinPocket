package com.twinsanityevolved.game;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;

/**
 * The game process's power and heat sampler (the performance report, P0): every 10 s -- the native census window's length
 * -- one line in the game's own log through TeDiag.nativeLog:
 *
 *   [te.android] power: temp_c=33.4 current_ma=812 voltage_v=3.91 power_w=3.17 source=battery level=78 thermal=0 headroom=0.41 refresh_hz=60.0
 *
 * The battery's temperature, voltage, level and power source come from the sticky ACTION_BATTERY_CHANGED intent (read, no
 * receiver registered); the current from BatteryManager (µA by the documentation; a device that reports mA is recognised by
 * the size: no handheld draws under 10 mA while it plays); the estimated power is current × voltage, on the battery only
 * (on the charger the current is the charge, not the game's draw). The thermal status (API 29) and headroom (API 30) from
 * PowerManager; the display's refresh rate from the activity's display. What the device does not tell is written "na".
 *
 * The cost: one low-priority thread asleep 10 s at a time, a few system calls per sample; nothing on the game's or the
 * drawing thread. ToolsCore reads the lines in the launcher, after the game.
 */
final class Telemetry {
    static final long PERIOD_MS = 10_000;

    private final Context app;
    private final Activity activity;
    private volatile boolean running;
    private Thread thread;
    private boolean nativeOk = true;
    private long lastCpuMs = -1, lastElapsedMs = -1;
    private long startedMs;

    Telemetry(Activity activity) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        startedMs = SystemClock.elapsedRealtime();
        lastCpuMs = lastElapsedMs = -1;
        nativeOk = true;
        thread = new Thread(this::loop, "te-telemetry");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (running && nativeOk && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!running) break;
            String line;
            try {
                line = sample();
            } catch (Throwable t) {
                line = "[te.android] power: sample failed (" + t.getClass().getSimpleName() + ")";
            }
            try {
                TeDiag.nativeLog(line);
            } catch (Throwable t) {   // libmain.so not loaded (a broken start): the sampler stops
                nativeOk = false;
                Diag.log(app, "game", "telemetry stopped: " + t);
            }
        }
    }

    String sample() {
        double tempC = -1, voltageV = -1, currentMa = -1, powerW = -1, headroom = -1, refreshHz = -1;
        int level = -1, thermal = -1;
        String source = null;

        Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);   // tenths of a degree
            if (t != Integer.MIN_VALUE && t > -400 && t < 1500) tempC = t / 10.0;
            int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) voltageV = mv > 100 ? mv / 1000.0 : mv;   // (a few devices report volts)
            int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lv >= 0 && sc > 0) level = Math.round(100f * lv / sc);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (plugged == 0) source = "battery";
            else if (plugged > 0) source = "charger";
        }
        BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) currentMa = ToolsCore.currentMa(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        powerW = ToolsCore.watts(currentMa, voltageV, source);

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= 29) thermal = pm.getCurrentThermalStatus();
            if (Build.VERSION.SDK_INT >= 30) {
                float h = pm.getThermalHeadroom(10);   // NaN where the device has no thermal model
                if (!Float.isNaN(h) && h >= 0) headroom = h;
            }
        }

        Display d = Build.VERSION.SDK_INT >= 30 ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        if (d != null) {
            float r = d.getRefreshRate();
            if (r > 0) refreshHz = r;
        }

        long elapsed = SystemClock.elapsedRealtime();
        long cpu = Process.getElapsedCpuTime();
        double cores = lastElapsedMs >= 0 && elapsed > lastElapsedMs && cpu >= lastCpuMs
                ? (double) (cpu - lastCpuMs) / (elapsed - lastElapsedMs) : -1;
        lastCpuMs = cpu;
        lastElapsedMs = elapsed;
        return ToolsCore.powerLine(tempC, currentMa, voltageV, powerW, source, level, thermal, headroom, refreshHz)
                + String.format(java.util.Locale.ROOT, " elapsed_s=%.1f process_cpu_cores=%s",
                    (elapsed - startedMs) / 1000.0, cores < 0 ? "na" : String.format(java.util.Locale.ROOT, "%.3f", cores));
    }
}
