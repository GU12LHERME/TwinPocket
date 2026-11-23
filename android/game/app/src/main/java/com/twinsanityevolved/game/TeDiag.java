package com.twinsanityevolved.game;

/** The native diagnostic probe in libmain.so (runner/te_android_diag.cpp): no game, no SDL window. */
final class TeDiag {
    private TeDiag() {}

    static native String nativeProbe(String filesDir, String externalFilesDir);

    /** One line in the game's own log (logcat and files/logs/latest-game.log, time-stamped): Telemetry's samples. */
    static native void nativeLog(String line);
}
