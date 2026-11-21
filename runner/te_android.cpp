// runner/te_android.cpp -- the Android lane's entry point (TE_ANDROID; CMakeLists.txt).
//
// SDL2's Java activity (com.twinsanityevolved.game.MainActivity, an SDLActivity running in the ":game" process) loads
// libSDL2.so and libmain.so and calls SDL_main on its own thread. When SDL_main returns, SDLActivity finishes: the game's
// window closes and the launcher (LauncherActivity, the app's own process) comes back with what this run reached.
//
// SDL_main here: the start-up stages and logs (te_android_diag.h: logcat tag TwinsanityEvolved + files/logs), the app's
// folders, the disc image, then the runner's main() (runner/main.cpp compiled as te_runner_main in this lane).
//   - No usable disc image: a message box says what to put where, and SDL_main returns without starting the game.
//   - "--android-sdl-test" (the launcher's "SDL test" button): an SDL window, a GLES renderer and the gamepad events for a
//     few seconds, without the game -- the Android interface, EGL and the pads tested apart from the engine.
//
// The folders: the app-specific external storage directory (SDL_AndroidGetExternalStoragePath:
// /storage/emulated/0/Android/data/<package>/files) is HOME and the working directory, so the runner's own lookups
// (te_startup.cpp: <home>/.twinsanityevolved for ps2r.ini and the memory cards) land inside the app's storage. The disc
// image is the user's own file, never part of the package (te::android::FindDiscImage).
#include <SDL.h>
#include <SDL_system.h>

#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

#include "te_android_diag.h"

int te_runner_main(int argc, char** argv);   // runner/main.cpp, renamed for this lane

namespace {

void ShowMessage(Uint32 flags, const char* title, const std::string& text) {
    te_android_log("message box: %s -- %s", title, text.c_str());
    if (SDL_ShowSimpleMessageBox(flags, title, text.c_str(), nullptr) != 0)
        te_android_log("SDL_ShowSimpleMessageBox failed: %s", SDL_GetError());
}

// The launcher's SDL test: a window, a GLES-backed SDL renderer, gamepad events; no engine, no game.
int SdlTest() {
    TE_ANDROID_STAGE("sdl test: SDL_Init(VIDEO | GAMECONTROLLER | EVENTS)");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_EVENTS) != 0) {
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved - SDL test", std::string("SDL_Init failed: ") + SDL_GetError());
        return 1;
    }
    TE_ANDROID_STAGE("sdl test: SDL_CreateWindow");
    SDL_Window* w = SDL_CreateWindow("TwinsanityEvolved SDL test", 0, 0, 0, 0, SDL_WINDOW_FULLSCREEN_DESKTOP);
    if (!w) {
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved - SDL test", std::string("SDL_CreateWindow failed: ") + SDL_GetError());
        return 1;
    }
    TE_ANDROID_STAGE("sdl test: SDL_CreateRenderer (OpenGL ES)");
    SDL_Renderer* r = SDL_CreateRenderer(w, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!r) {
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved - SDL test", std::string("SDL_CreateRenderer failed: ") + SDL_GetError());
        return 1;
    }
    SDL_RendererInfo info{};
    SDL_GetRendererInfo(r, &info);
    int ww = 0, wh = 0;
    SDL_GetWindowSize(w, &ww, &wh);
    te_android_log("sdl test: renderer %s, window %dx%d, %d joysticks", info.name, ww, wh, SDL_NumJoysticks());
    for (int i = 0; i < SDL_NumJoysticks(); ++i) {
        if (SDL_IsGameController(i) && SDL_GameControllerOpen(i))
            te_android_log("sdl test: game controller %d: %s", i, SDL_GameControllerNameForIndex(i));
        else
            te_android_log("sdl test: joystick %d (no controller mapping): %s", i, SDL_JoystickNameForIndex(i));
    }
    TE_ANDROID_STAGE("sdl test: drawing (10 s; B / Back ends it)");
    const Uint32 start = SDL_GetTicks();
    int presses = 0;
    bool run = true;
    Uint8 shade = 40;
    while (run && SDL_GetTicks() - start < 10000) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_QUIT) run = false;
            if (e.type == SDL_CONTROLLERBUTTONDOWN) {
                ++presses;
                te_android_log("sdl test: button %s",
                               SDL_GameControllerGetStringForButton(static_cast<SDL_GameControllerButton>(e.cbutton.button)));
                if (e.cbutton.button == SDL_CONTROLLER_BUTTON_B || e.cbutton.button == SDL_CONTROLLER_BUTTON_BACK) run = false;
                shade = static_cast<Uint8>(shade + 50);
            }
            if (e.type == SDL_KEYDOWN) {
                te_android_log("sdl test: key %s", SDL_GetKeyName(e.key.keysym.sym));
                if (e.key.keysym.sym == SDLK_AC_BACK) run = false;
            }
        }
        const Uint32 t = SDL_GetTicks() - start;
        SDL_SetRenderDrawColor(r, 20, static_cast<Uint8>(60 + (t / 20) % 120), shade, 255);
        SDL_RenderClear(r);
        SDL_Rect bar{0, wh - 40, static_cast<int>(static_cast<long>(ww) * t / 10000), 40};
        SDL_SetRenderDrawColor(r, 240, 160, 30, 255);
        SDL_RenderFillRect(r, &bar);
        SDL_RenderPresent(r);
    }
    te_android_log("sdl test: done, %d button presses", presses);
    SDL_DestroyRenderer(r);
    SDL_DestroyWindow(w);
    SDL_Quit();
    return 0;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) int SDL_main(int argc, char** argv) {
    te_android_thread_altstack();
    te_android_redirect_stdio();
    TE_ANDROID_STAGE("SDL_main entered (SDL thread)");
    for (int i = 0; i < argc; ++i) te_android_log("argv[%d] = %s", i, argv[i] ? argv[i] : "(null)");

    bool sdl_test = false;
    for (int i = 1; i < argc; ++i)
        if (argv[i] && std::strcmp(argv[i], "--android-sdl-test") == 0) sdl_test = true;
    if (sdl_test) {
        const int rc = SdlTest();
        te_android_finished(rc);
        return rc;
    }

    TE_ANDROID_STAGE("app folders (SDL_AndroidGetExternalStoragePath)");
    const char* external = SDL_AndroidGetExternalStoragePath();
    const char* internal = SDL_AndroidGetInternalStoragePath();
    te_android_log("external files folder: %s; internal files folder: %s", external ? external : "(none)",
                   internal ? internal : "(none)");
    const std::string files = external && *external ? external : internal && *internal ? internal : "";
    if (files.empty()) {
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved",
                    std::string("O Android não informou a pasta do aplicativo (") + SDL_GetError() + "). O jogo não foi iniciado.");
        te_android_finished(3);
        return 3;
    }
    std::error_code ec;
    std::filesystem::create_directories(files, ec);
    ::setenv("HOME", files.c_str(), 1);
    if (::chdir(files.c_str()) != 0) te_android_log("chdir(%s) failed", files.c_str());

    TE_ANDROID_STAGE("disc image lookup");
    std::string how;
    const std::string iso = te::android::FindDiscImage(files, &how);
    std::string report;
    if (iso.empty() || !te::android::CheckDiscImage(iso, &report)) {
        const std::string why = iso.empty() ? how : report;
        ShowMessage(SDL_MESSAGEBOX_WARNING, "TwinsanityEvolved - imagem do disco",
                    "Nenhuma imagem de disco utilizável foi encontrada, então o jogo não foi iniciado.\n\n"
                    "Copie a SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00) para:\n" +
                        files + "/twinsanity.iso\n\nDetalhe: " + why);
        te_android_finished(2);
        return 2;
    }
    te_android_log("disc image: %s", report.c_str());

    std::vector<std::string> args;
    args.emplace_back(argc > 0 && argv && argv[0] ? argv[0] : "twinsanityevolved");
    for (int i = 1; i < argc; ++i) args.emplace_back(argv[i]);
    args.emplace_back("--iso");
    args.push_back(iso);
    std::vector<char*> cargs;
    for (std::string& a : args) cargs.push_back(a.data());
    cargs.push_back(nullptr);

    TE_ANDROID_STAGE("entering the runner (te_runner_main)");
    const int rc = te_runner_main(static_cast<int>(args.size()), cargs.data());
    std::fflush(stdout);
    std::fflush(stderr);
    if (rc != 0)
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved",
                    "O jogo terminou com o código " + std::to_string(rc) +
                        ". Volte ao inicializador e use \"Compartilhar relatório\" para enviar os logs.");
    te_android_finished(rc);
    return rc;
}
