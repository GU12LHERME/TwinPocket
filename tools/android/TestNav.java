package com.twinsanityevolved.game;

// The launcher's navigation logic (android/game/.../Nav.java) on a plain JVM: the repeat of a held direction, the stick's
// deadzone and hysteresis, the selection over enabled items, the tabs and when START may start the game.
public final class TestNav {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // ---- the repeat
        Nav.Repeater r = new Nav.Repeater();
        check(r.press(Nav.DOWN, 0) == Nav.DOWN, "press: one step at once");
        check(r.press(Nav.DOWN, 10) == Nav.NONE, "the same direction again (Android's repeat): no second step");
        check(r.tick(Nav.REPEAT_DELAY_MS - 1) == Nav.NONE, "no repeat before the initial delay");
        check(r.tick(Nav.REPEAT_DELAY_MS) == Nav.DOWN, "first repeat at the delay");
        check(r.tick(Nav.REPEAT_DELAY_MS + 1) == Nav.NONE, "no double step right after");
        check(r.tick(Nav.REPEAT_DELAY_MS + Nav.REPEAT_INTERVAL_MS) == Nav.DOWN, "then one per interval");
        check(r.tick(Nav.REPEAT_DELAY_MS + 10 * Nav.REPEAT_INTERVAL_MS) == Nav.DOWN
                && r.tick(Nav.REPEAT_DELAY_MS + 10 * Nav.REPEAT_INTERVAL_MS) == Nav.NONE, "a late tick gives one step, never a burst");
        check(r.press(Nav.UP, 2000) == Nav.UP, "a new direction steps at once");
        r.release();
        check(!r.held() && r.tick(99999) == Nav.NONE, "released: no repeat");
        check(r.press(Nav.UP, 100000) == Nav.UP, "pressed again after release: a step");

        // ---- the stick
        check(Nav.stickDir(0.10f, 0.20f, Nav.NONE) == Nav.NONE, "small drift inside the deadzone: nothing");
        check(Nav.stickDir(0.0f, 0.49f, Nav.NONE) == Nav.NONE, "just under the threshold: nothing");
        check(Nav.stickDir(0.0f, 0.6f, Nav.NONE) == Nav.DOWN, "past the threshold down: DOWN");
        check(Nav.stickDir(0.0f, -0.6f, Nav.NONE) == Nav.UP, "past the threshold up: UP");
        check(Nav.stickDir(0.0f, 0.40f, Nav.DOWN) == Nav.DOWN, "hysteresis: held between OFF and ON");
        check(Nav.stickDir(0.0f, 0.30f, Nav.DOWN) == Nav.NONE, "below OFF: released");
        check(Nav.stickDir(0.45f, 0.42f, Nav.DOWN) == Nav.DOWN, "a diagonal wobble does not turn it");
        check(Nav.stickDir(0.8f, 0.4f, Nav.DOWN) == Nav.RIGHT, "a clear turn to the other axis: RIGHT");
        check(Nav.stickDir(0.0f, -0.7f, Nav.DOWN) == Nav.UP, "flipped to the other side: UP");
        check(Nav.stickDir(-0.9f, 0.1f, Nav.NONE) == Nav.LEFT, "left");

        // ---- the selection
        boolean[] en = {true, false, true, true, false};
        check(Nav.move(en, 0, 1) == 2, "down skips a disabled item");
        check(Nav.move(en, 3, 1) == 3, "down at the last enabled item: stays (no wrap)");
        check(Nav.move(en, 0, -1) == 0, "up at the first: stays");
        check(Nav.move(en, 2, -1) == 0, "up skips a disabled item");
        check(Nav.firstEnabled(en, 1) == 2, "a remembered disabled item: the next enabled one");
        check(Nav.firstEnabled(en, 9) == 3, "a remembered index past the end: the last enabled one");
        check(Nav.firstEnabled(new boolean[] {false, false}, 0) == -1, "no enabled item: none selected");
        check(Nav.move(new boolean[0], 0, 1) == -1, "an empty page: none");

        // ---- tabs and START
        check(Nav.nextTab(0, -1, 5) == 4 && Nav.nextTab(4, 1, 5) == 0 && Nav.nextTab(2, 1, 5) == 3, "LB / RB round the tabs");
        check(Nav.startAllowed(Nav.TAB_PLAY, false, false, false, false), "START on the Jogar tab: plays");
        check(!Nav.startAllowed(2, false, false, false, false), "START on another tab: does not play");
        check(!Nav.startAllowed(Nav.TAB_PLAY, true, false, false, false), "START under a dialog: does not play");
        check(!Nav.startAllowed(Nav.TAB_PLAY, false, true, false, false), "START with the file picker open: does not play");
        check(!Nav.startAllowed(Nav.TAB_PLAY, false, false, true, false), "START while the ISO is copied: does not play");
        check(!Nav.startAllowed(Nav.TAB_PLAY, false, false, false, true), "START while starting: no second start");

        System.out.println(failures == 0 ? "TestNav: ok" : "TestNav: FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
