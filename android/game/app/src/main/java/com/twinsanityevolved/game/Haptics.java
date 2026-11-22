package com.twinsanityevolved.game;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The launcher's haptic feedback (not the game's vibration: that one is the game's own, played by the native side,
 * platform/nativeee/host/rumble.cpp, in the game's process). One short tap when a choice is confirmed (A, a dialog's
 * button) and a lighter one when the tab changes (LB / RB). Nothing on the d-pad's or the stick's moves, nothing held.
 *
 * The preference: ps2r.ini android.launcher_haptics = on | off (on by default; Controles > "Vibração no launcher"), read
 * once and kept (refresh() after the row changes it): no file read on a press. The vibrator's call is asynchronous
 * (Android queues the effect): the screen never waits for the motor. cancel() stops whatever is still playing when the
 * launcher goes to the background (the game starting, the home screen).
 *
 * The motor: the controller that sent the key when Android exposes one (InputDevice.getVibrator), else the device's own
 * vibrator (the Retroid Pocket 6's built-in controls are part of the device). VIBRATE is a normal permission.
 */
final class Haptics {
    static final String KEY = "android.launcher_haptics";

    private static Boolean enabled;
    private static Vibrator last;

    private Haptics() {}

    static void refresh(Context c) {
        enabled = !"off".equals(new Settings(c).get(KEY, "on"));
    }

    static boolean enabled(Context c) {
        if (enabled == null) refresh(c);
        return enabled;
    }

    /** A confirm (strong = true) or a tab change (false), from the key event that caused it (null: a touch). */
    static void tap(Context c, KeyEvent e, boolean strong) {
        try {
            if (!enabled(c)) return;
            Vibrator v = null;
            if (e != null) {
                InputDevice dev = InputDevice.getDevice(e.getDeviceId());
                if (dev != null && !dev.isVirtual()) {
                    Vibrator pad = dev.getVibrator();
                    if (pad != null && pad.hasVibrator()) v = pad;
                }
            }
            if (v == null) v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            last = v;
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

    /** The launcher leaves the screen: an effect still playing is stopped. */
    static void cancel() {
        try {
            if (last != null) last.cancel();
        } catch (Exception ignored) {
        }
        last = null;
    }
}
