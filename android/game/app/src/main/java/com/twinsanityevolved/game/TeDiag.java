package com.twinsanityevolved.game;

/** The native diagnostic probe in libmain.so (runner/te_android_diag.cpp): no game, no SDL window. */
final class TeDiag {
    private TeDiag() {}

    static native String nativeProbe(String filesDir, String externalFilesDir);
}
