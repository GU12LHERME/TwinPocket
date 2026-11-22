package com.twinsanityevolved.game;

/**
 * The launcher's controller navigation, as plain logic (no Android: tools/android/TestNav.java runs it on the CI's JVM).
 *
 * - Repeater: a held direction (the d-pad, the left stick) gives one step at the press, then -- after REPEAT_DELAY_MS --
 *   one step every REPEAT_INTERVAL_MS until it is released. Android's own key repeats are ignored by the launcher: this is
 *   the only repeat.
 * - stickDir: the left stick as a direction, with hysteresis -- on past STICK_ON along its main axis, held until it falls
 *   below STICK_OFF (a stick resting near the threshold or drifting does not flicker); a turn to the other axis counts only
 *   once that axis is past STICK_ON and the larger one.
 * - move / firstEnabled: the selection over a page's items -- disabled items skipped, stopped at the ends (no jump from
 *   the last item to the first).
 * - startAllowed: START plays only on the Jogar tab, never under a dialog, the file picker, a copy or a start in progress.
 */
final class Nav {
    static final long REPEAT_DELAY_MS = 350;
    static final long REPEAT_INTERVAL_MS = 90;
    static final float STICK_ON = 0.5f;
    static final float STICK_OFF = 0.35f;

    static final int NONE = 0, UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4;

    static final int TAB_PLAY = 0;

    private Nav() {}

    static final class Repeater {
        private int dir = NONE;
        private long next;

        /** A direction pressed (or the stick's new direction): its step, once -- NONE while it stays the same. */
        int press(int d, long now) {
            if (d == NONE) {
                dir = NONE;
                return NONE;
            }
            if (d == dir) return NONE;
            dir = d;
            next = now + REPEAT_DELAY_MS;
            return d;
        }

        void release() {
            dir = NONE;
        }

        /** The repeat's step when one is due, else NONE (at most one a call: a late call never bursts several steps). */
        int tick(long now) {
            if (dir == NONE || now < next) return NONE;
            next += REPEAT_INTERVAL_MS;
            if (next <= now) next = now + REPEAT_INTERVAL_MS;
            return dir;
        }

        boolean held() {
            return dir != NONE;
        }

        int dir() {
            return dir;
        }
    }

    /** The stick's direction now, given the one it held (Android's axes: y negative is up). */
    static int stickDir(float x, float y, int current) {
        float ax = Math.abs(x), ay = Math.abs(y);
        if (current != NONE) {
            boolean vertical = current == UP || current == DOWN;
            float v = vertical ? y : x;
            boolean sameSign = (current == UP || current == LEFT) ? v < 0 : v > 0;
            float mag = Math.abs(v);
            float other = vertical ? ax : ay;
            if (sameSign && mag >= STICK_OFF && !(other >= STICK_ON && other > mag)) return current;
        }
        if (Math.max(ax, ay) < STICK_ON) return NONE;
        if (ay >= ax) return y < 0 ? UP : DOWN;
        return x < 0 ? LEFT : RIGHT;
    }

    /** The item a move by `delta` (-1 / +1) from `index` lands on: the next enabled one, or `index` at the end. */
    static int move(boolean[] enabled, int index, int delta) {
        if (enabled.length == 0) return -1;
        for (int i = index + delta; i >= 0 && i < enabled.length; i += delta)
            if (enabled[i]) return i;
        return index;
    }

    /** A valid selection: `preferred` when it is enabled, else the nearest enabled item after it, then before; -1 for none. */
    static int firstEnabled(boolean[] enabled, int preferred) {
        if (enabled.length == 0) return -1;
        int p = Math.max(0, Math.min(enabled.length - 1, preferred));
        if (enabled[p]) return p;
        for (int i = p + 1; i < enabled.length; i++) if (enabled[i]) return i;
        for (int i = p - 1; i >= 0; i--) if (enabled[i]) return i;
        return -1;
    }

    /** The tab after a shoulder press (LB -1, RB +1), round the ends. */
    static int nextTab(int tab, int delta, int count) {
        return ((tab + delta) % count + count) % count;
    }

    static boolean startAllowed(int tab, boolean dialogOpen, boolean pickerOpen, boolean copying, boolean starting) {
        return tab == TAB_PLAY && !dialogOpen && !pickerOpen && !copying && !starting;
    }
}
