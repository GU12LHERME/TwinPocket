package com.twinsanityevolved.game;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The launcher's haptic feedback: one short tap when a choice is confirmed (A / Enter / Start) and a lighter one when the
 * tab changes (L1 / R1). Nothing on the d-pad's moves, nothing continuous, nothing while the game runs (the game is another
 * process and never calls this).
 *
 * The preference: ps2r.ini android.launcher_haptics = on | off (on by default; Controles > "Vibração no launcher"). The game's
 * own Vibration option lives in its save on the memory card (progress.h options.vibration), which the launcher does not
 * parse; and the port draws no rumble from it yet (platform/nativeee/host/pads.cpp: no motor output is installed), so the
 * launcher keeps a switch of its own in the shared settings file.
 *
 * The motor: the controller that sent the key when Android exposes one (InputDevice.getVibrator -- an external pad that
 * rumbles through Android), else the device's own vibrator (the Retroid Pocket 6's built-in controls are part of the device).
 * VIBRATE is a normal permission: granted at install, nothing asked.
 */
final class Haptics {
    static final String KEY = "android.launcher_haptics";

    private Haptics() {}

    static boolean enabled(Context c) {
        return !"off".equals(new Settings(c).get(KEY, "on"));
    }

    /** A confirm (strong = true) or a tab change (false), from the key event that caused it (null: a touch). */
    static void tap(Context c, KeyEvent e, boolean strong) {
        try {
            if (!enabled(c)) return;
            Vibrator v = null;
            if (e != null && Build.VERSION.SDK_INT >= 16) {
                InputDevice dev = InputDevice.getDevice(e.getDeviceId());
                if (dev != null && !dev.isVirtual()) {
                    Vibrator pad = dev.getVibrator();
                    if (pad != null && pad.hasVibrator()) v = pad;
                }
            }
            if (v == null) v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(strong ? 20 : 10, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(strong ? 20 : 10);
            }
        } catch (Exception ignored) {
            // no motor, a driver that refuses, a missing permission: no feedback, never a crash
        }
    }
}
