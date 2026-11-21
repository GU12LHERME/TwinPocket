package com.twinsanityevolved.game;

import org.libsdl.app.SDLActivity;

/**
 * SDL2's Android Activity owns the native window, the EGL surface, the gamepads and the audio device, and calls
 * SDL_main in libmain.so (runner/te_android.cpp) -- the TwinsanityEvolved runner, engine and game module.
 */
public final class MainActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "main"};
    }
}
