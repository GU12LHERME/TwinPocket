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

#include <alloca.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

#include <jni.h>

#include "te_android_diag.h"

int te_runner_main(int argc, char** argv);   // runner/main.cpp, renamed for this lane

// --- the game thread and the game's stack ---------------------------------------------------------------------------------
// The runner does not run on SDL's thread ("SDLThread": a Java thread with ART's default 1 MB stack). It runs on a
// native thread of its own with a large stack (kGameThreadStack), attached to the JVM through SDL, and the game fiber's
// stack (runner/te_game.h kStackSize, 32 MB) is a region of THAT stack (te_android_take_fiber_stack, used by
// te_fiber.c te_fiber_map_stack): every frame the game's code makes -- including SDL's JNI calls made from it -- lies
// inside the stack bounds ART recorded for the calling thread. On the separate mapping the desktop uses, ART saw those
// frames below the thread's stack end and threw StackOverflowError at the first JNI call (the device report, stage 11).
namespace {
constexpr size_t kGameThreadStack = 160u << 20;   // 160 MB of address space (pages are touched only as used)
constexpr size_t kFiberRegion = (32u << 20) + (1u << 20);   // the game fiber's 32 MB + slack
constexpr size_t kFiberGuard = 64u << 10;          // a no-access band at the region's low end (an overflow faults)
uint8_t* g_fiber_region = nullptr;                  // [g_fiber_region, + kFiberRegion) inside the game thread's stack
bool g_fiber_taken = false;
}  // namespace

extern "C" void* te_android_take_fiber_stack(size_t size) {
    if (!g_fiber_region || g_fiber_taken || size + kFiberGuard > kFiberRegion) {
        te_android_log("game stack: no region from the game thread for %zu bytes (region %p, taken %d) -- a separate mapping is used",
                       size, static_cast<void*>(g_fiber_region), g_fiber_taken ? 1 : 0);
        return nullptr;
    }
    g_fiber_taken = true;
    // the region's lowest page-aligned band no-access: the fiber's overflow faults there instead of writing into the
    // host's frames below
    const uintptr_t page = static_cast<uintptr_t>(::sysconf(_SC_PAGESIZE));
    const uintptr_t lo = (reinterpret_cast<uintptr_t>(g_fiber_region) + page - 1) & ~(page - 1);
    const int prot = ::mprotect(reinterpret_cast<void*>(lo), kFiberGuard, PROT_NONE);
    uint8_t* stack = reinterpret_cast<uint8_t*>(lo + kFiberGuard);
    te_android_log("game stack: %zu bytes at [%p, %p) inside the game thread's stack (guard %s)", size, static_cast<void*>(stack),
                   static_cast<void*>(stack + size), prot == 0 ? "on" : "not set");
    return stack;
}

extern "C" int te_android_is_fiber_stack(const void* stack) {
    const uint8_t* p = static_cast<const uint8_t*>(stack);
    return g_fiber_region && p >= g_fiber_region && p < g_fiber_region + kFiberRegion;
}

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
    // the game folder (ps2r.ini, the memory cards, mods): private internal storage (runner/te_startup.cpp ExecutableDir
    // reads TE_ANDROID_GAME_DIR on this lane; the executable's own folder is /system/bin, which no app can write)
    if (internal && *internal) {
        const std::string game_dir = std::string(internal) + "/game";
        std::filesystem::create_directories(game_dir + "/userdata", ec);
        ::setenv("TE_ANDROID_GAME_DIR", game_dir.c_str(), 1);
        te_android_log("game folder (settings, memory cards): %s", game_dir.c_str());
    }
    if (::chdir(files.c_str()) != 0) te_android_log("chdir(%s) failed", files.c_str());

    TE_ANDROID_STAGE("disc image lookup");
    // 1. --te-iso <path>: the image the launcher checked (normally its copy in files/disc, made through Android's
    //    document picker); 2. that copy; 3. the lookup in the external app folder (FindDiscImage)
    std::string how, iso;
    std::vector<std::string> runner_args;
    for (int i = 1; i < argc; ++i) {
        if (argv[i] && std::strcmp(argv[i], "--te-iso") == 0 && i + 1 < argc) {
            iso = argv[++i];
            how = "given by the launcher (--te-iso)";
        } else {
            runner_args.emplace_back(argv[i] ? argv[i] : "");
        }
    }
    if (iso.empty() && internal && *internal) {
        const std::string imported = std::string(internal) + "/disc/twinsanity.iso";
        if (std::filesystem::is_regular_file(imported, ec)) {
            iso = imported;
            how = "the copy made by the launcher's ISO picker (files/disc)";
        }
    }
    if (iso.empty()) iso = te::android::FindDiscImage(files, &how);
    te_android_log("disc image candidate: %s (%s)", iso.empty() ? "(none)" : iso.c_str(), how.c_str());
    std::string report;
    if (iso.empty() || !te::android::CheckDiscImage(iso, &report)) {
        const std::string why = iso.empty() ? how : report;
        ShowMessage(SDL_MESSAGEBOX_WARNING, "TwinsanityEvolved - imagem do disco",
                    "Nenhuma imagem de disco utilizável foi encontrada, então o jogo não foi iniciado.\n\n"
                    "Volte ao inicializador, toque em \"Selecionar ISO\" e escolha a SUA imagem do Crash Twinsanity "
                    "(PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00) no gerenciador de arquivos.\n\nDetalhe: " + why);
        te_android_finished(2);
        return 2;
    }
    te_android_log("disc image: %s", report.c_str());

    std::vector<std::string> args;
    args.emplace_back(argc > 0 && argv && argv[0] ? argv[0] : "twinsanityevolved");
    for (const std::string& a : runner_args) args.push_back(a);
    args.emplace_back("--iso");
    args.push_back(iso);
    std::vector<char*> cargs;
    for (std::string& a : args) cargs.push_back(a.data());
    cargs.push_back(nullptr);

    TE_ANDROID_STAGE("starting the game thread (large native stack, attached to the JVM)");
    struct GameStart {
        int argc;
        char** argv;
        int rc;
    } start{static_cast<int>(args.size()), cargs.data(), 99};
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, kGameThreadStack);
    pthread_t game;
    const int created = pthread_create(&game, &attr, [](void* opaque) -> void* {
        auto* st = static_cast<GameStart*>(opaque);
        pthread_setname_np(pthread_self(), "TEGame");
        te_android_thread_altstack();
        // attach this thread to the JVM the way SDL does it (SDL's own key detaches it at the thread's end)
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        pthread_attr_t self;
        void* lo = nullptr;
        size_t sz = 0;
        if (pthread_getattr_np(pthread_self(), &self) == 0) {
            pthread_attr_getstack(&self, &lo, &sz);
            pthread_attr_destroy(&self);
        }
        // the game fiber's stack: a region of this frame (alloca), above everything the runner calls from here
        g_fiber_region = static_cast<uint8_t*>(alloca(kFiberRegion));
        g_fiber_taken = false;
        te_android_log("game thread: JNIEnv %p, stack [%p, %p) %zu MB, fiber region [%p, %p)", static_cast<void*>(env), lo,
                       static_cast<void*>(static_cast<uint8_t*>(lo) + sz), sz >> 20, static_cast<void*>(g_fiber_region),
                       static_cast<void*>(g_fiber_region + kFiberRegion));
        TE_ANDROID_STAGE("entering the runner (te_runner_main) on the game thread");
        st->rc = te_runner_main(st->argc, st->argv);
        g_fiber_region = nullptr;
        return nullptr;
    }, &start);
    pthread_attr_destroy(&attr);
    if (created != 0) {
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved", "Não foi possível criar a thread do jogo (erro " + std::to_string(created) + ").");
        te_android_finished(4);
        return 4;
    }
    pthread_join(game, nullptr);
    const int rc = start.rc;
    std::fflush(stdout);
    std::fflush(stderr);
    if (rc != 0)
        ShowMessage(SDL_MESSAGEBOX_ERROR, "TwinsanityEvolved",
                    "O jogo terminou com o código " + std::to_string(rc) +
                        ". Volte ao inicializador e use \"Compartilhar relatório\" para enviar os logs.");
    te_android_finished(rc);
    return rc;
}
