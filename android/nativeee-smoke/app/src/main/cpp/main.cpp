#include <SDL.h>

#include <array>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <string>

#include "engine/core/log/logger.h"
#include "engine/platform/android/platform_android.h"
#include "engine/platform/platform.h"

#if !defined(__ANDROID__) || !defined(__aarch64__)
#error "This APK smoke app must target Android arm64-v8a"
#endif
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 24
#error "This APK smoke app requires Android API 24 or newer"
#endif

namespace {

constexpr int kWidth = 960;
constexpr int kHeight = 540;
struct Glyph { char c; std::array<unsigned char, 7> rows; };
constexpr Glyph kFont[] = {
    {' ', {0,0,0,0,0,0,0}}, {'2', {14,17,1,6,8,16,31}},
    {'A', {14,17,17,31,17,17,17}}, {'B', {30,17,17,30,17,17,30}},
    {'C', {14,17,16,16,16,17,14}}, {'D', {30,17,17,17,17,17,30}},
    {'E', {31,16,16,30,16,16,31}}, {'F', {31,16,16,30,16,16,16}},
    {'G', {14,17,16,23,17,17,15}}, {'H', {17,17,17,31,17,17,17}},
    {'I', {31,4,4,4,4,4,31}}, {'J', {7,2,2,2,18,18,12}},
    {'K', {17,18,20,24,20,18,17}}, {'L', {16,16,16,16,16,16,31}},
    {'M', {17,27,21,21,17,17,17}}, {'N', {17,25,21,19,17,17,17}},
    {'O', {14,17,17,17,17,17,14}}, {'P', {30,17,17,30,16,16,16}},
    {'Q', {14,17,17,17,21,18,13}}, {'R', {30,17,17,30,20,18,17}},
    {'S', {15,16,16,14,1,1,30}}, {'T', {31,4,4,4,4,4,4}},
    {'U', {17,17,17,17,17,17,14}}, {'V', {17,17,17,17,17,10,4}},
    {'W', {17,17,17,21,21,21,10}}, {'X', {17,17,10,4,10,17,17}},
    {'Y', {17,17,10,4,4,4,4}}, {'Z', {31,1,2,4,8,16,31}},
};

struct Check { const char* label; bool passed = false; };
std::array<Check, 6> g_checks{{
    {"BOOTSTRAP INIT", false}, {"MONOTONIC CLOCK", false},
    {"CORE LOGGER", false}, {"THREAD CREATE JOIN", false},
    {"PRIVATE FILE WRITE", false}, {"PRIVATE FILE READ", false},
}};
SDL_GameController* g_controller = nullptr;
SDL_Joystick* g_joystick = nullptr;
SDL_JoystickID g_device_id = -1;
SDL_Renderer* g_renderer = nullptr;
bool g_key_up = false, g_key_down = false, g_key_left = false, g_key_right = false;
bool g_key_a = false, g_key_b = false, g_nativeee_initialized = false;

struct Controls { bool up=false, down=false, left=false, right=false; bool a=false, b=false, x=false, y=false, connected=false; };

const Glyph* FindGlyph(char c) {
    for (const auto& glyph : kFont) if (glyph.c == c) return &glyph;
    return nullptr;
}

void DrawText(const char* text, int x, int y, int scale, SDL_Color color) {
    SDL_SetRenderDrawColor(g_renderer, color.r, color.g, color.b, color.a);
    for (const char* p = text; *p; ++p, x += 6 * scale) {
        const Glyph* glyph = FindGlyph(*p);
        if (!glyph) continue;
        for (int row = 0; row < 7; ++row) for (int col = 0; col < 5; ++col) {
            if ((glyph->rows[static_cast<size_t>(row)] & (1u << (4 - col))) == 0) continue;
            SDL_Rect pixel{x + col * scale, y + row * scale, scale, scale};
            SDL_RenderFillRect(g_renderer, &pixel);
        }
    }
}

void FillRect(int x, int y, int w, int h, SDL_Color color) {
    SDL_SetRenderDrawColor(g_renderer, color.r, color.g, color.b, color.a);
    SDL_Rect rect{x, y, w, h};
    SDL_RenderFillRect(g_renderer, &rect);
}

void FillCircle(int cx, int cy, int radius, SDL_Color color) {
    SDL_SetRenderDrawColor(g_renderer, color.r, color.g, color.b, color.a);
    for (int y = -radius; y <= radius; ++y) for (int x = -radius; x <= radius; ++x) {
        if (x*x + y*y <= radius*radius) {
            SDL_Rect pixel{cx+x, cy+y, 1, 1};
            SDL_RenderFillRect(g_renderer, &pixel);
        }
    }
}

void DrawDpad(const Controls& c) {
    const SDL_Color off{49,61,87,255}, on{69,211,190,255};
    FillRect(390, 365, 170, 58, off); FillRect(446, 309, 58, 170, off);
    if (c.left) FillRect(390,365,56,58,on);
    if (c.right) FillRect(504,365,56,58,on);
    if (c.up) FillRect(446,309,58,56,on);
    if (c.down) FillRect(446,421,58,58,on);
    FillRect(453,372,44,44,SDL_Color{28,37,57,255});
}

void DrawButton(int x, int y, char label, bool pressed, SDL_Color tint) {
    FillCircle(x, y, 29, pressed ? SDL_Color{246,237,206,255} : tint);
    char text[2]{label, '\0'};
    DrawText(text, x-5, y-7, 2, SDL_Color{19,26,43,255});
}

void DrawFrame(const Controls& c, bool paused) {
    SDL_SetRenderDrawColor(g_renderer, 16,23,42,255);
    SDL_RenderClear(g_renderer);
    FillRect(0,0,kWidth,105, paused ? SDL_Color{125,75,38,255} : SDL_Color{25,83,104,255});
    DrawText("SDL2 ARM64", 48, 24, 5, SDL_Color{238,244,250,255});
    DrawText(paused ? "PAUSED" : "NATIVE CORE TEST", 590, 38, 2, SDL_Color{238,244,250,255});
    DrawText("NATIVE TESTS", 55, 119, 2, SDL_Color{141,164,193,255});
    for (size_t i=0; i<g_checks.size(); ++i) {
        const int y = 151 + static_cast<int>(i)*31;
        DrawText(g_checks[i].label, 55, y, 2, SDL_Color{225,232,243,255});
        DrawText(g_checks[i].passed ? "PASS" : "FAIL", 307, y, 2,
                 g_checks[i].passed ? SDL_Color{69,211,190,255} : SDL_Color{248,105,105,255});
    }
    DrawText("PAD INPUT TEST", 55, 478, 2, SDL_Color{141,164,193,255});
    FillCircle(884, 52, 12, c.connected ? SDL_Color{69,211,190,255} : SDL_Color{115,129,154,255});
    DrawDpad(c);
    DrawButton(760,350,'Y',c.y,SDL_Color{202,94,108,255});
    DrawButton(818,408,'B',c.b,SDL_Color{212,96,92,255});
    DrawButton(702,408,'X',c.x,SDL_Color{77,146,222,255});
    DrawButton(760,466,'A',c.a,SDL_Color{72,189,130,255});
    SDL_RenderPresent(g_renderer);
}

void CloseController() {
    if (g_controller) SDL_GameControllerClose(g_controller);
    if (g_joystick) SDL_JoystickClose(g_joystick);
    g_controller=nullptr; g_joystick=nullptr; g_device_id=-1;
}

void OpenController(int index) {
    if (g_controller || g_joystick || index<0) return;
    if (SDL_IsGameController(index)) {
        g_controller=SDL_GameControllerOpen(index);
        if (g_controller) { g_device_id=SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(g_controller)); return; }
    }
    g_joystick=SDL_JoystickOpen(index);
    if (g_joystick) g_device_id=SDL_JoystickInstanceID(g_joystick);
}

void OpenFirstController() {
    for (int i=0; i<SDL_NumJoysticks(); ++i) { OpenController(i); if (g_controller || g_joystick) return; }
}

void SetKey(SDL_Keycode key, bool down) {
    switch(key) {
        case SDLK_UP: case SDLK_w: g_key_up=down; break;
        case SDLK_DOWN: case SDLK_s: g_key_down=down; break;
        case SDLK_LEFT: case SDLK_a: g_key_left=down; break;
        case SDLK_RIGHT: case SDLK_d: g_key_right=down; break;
        case SDLK_SPACE: case SDLK_z: g_key_a=down; break;
        case SDLK_LCTRL: case SDLK_x: g_key_b=down; break;
        default: break;
    }
}

void HandleEvent(const SDL_Event& e, bool& running, bool& paused) {
    switch(e.type) {
        case SDL_QUIT: case SDL_APP_TERMINATING: running=false; break;
        case SDL_APP_WILLENTERBACKGROUND: paused=true; break;
        case SDL_APP_DIDENTERFOREGROUND: paused=false; break;
        case SDL_WINDOWEVENT:
            if (e.window.event==SDL_WINDOWEVENT_MINIMIZED || e.window.event==SDL_WINDOWEVENT_HIDDEN) paused=true;
            if (e.window.event==SDL_WINDOWEVENT_RESTORED || e.window.event==SDL_WINDOWEVENT_SHOWN) paused=false;
            break;
        case SDL_KEYDOWN:
            if (e.key.keysym.sym==SDLK_ESCAPE || e.key.keysym.sym==SDLK_AC_BACK) running=false;
            else SetKey(e.key.keysym.sym,true);
            break;
        case SDL_KEYUP: SetKey(e.key.keysym.sym,false); break;
        case SDL_CONTROLLERDEVICEADDED: OpenController(e.cdevice.which); break;
        case SDL_JOYDEVICEADDED: OpenController(e.jdevice.which); break;
        case SDL_CONTROLLERDEVICEREMOVED:
            if (e.cdevice.which==g_device_id) { CloseController(); OpenFirstController(); }
            break;
        case SDL_JOYDEVICEREMOVED:
            if (e.jdevice.which==g_device_id) { CloseController(); OpenFirstController(); }
            break;
        default: break;
    }
}

Controls ReadControls() {
    Controls c;
    if (g_controller) {
        SDL_GameControllerUpdate();
        c.connected=SDL_GameControllerGetAttached(g_controller)==SDL_TRUE;
        const int x=SDL_GameControllerGetAxis(g_controller,SDL_CONTROLLER_AXIS_LEFTX);
        const int y=SDL_GameControllerGetAxis(g_controller,SDL_CONTROLLER_AXIS_LEFTY);
        c.left=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_DPAD_LEFT)||x < -10000;
        c.right=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_DPAD_RIGHT)||x > 10000;
        c.up=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_DPAD_UP)||y < -10000;
        c.down=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_DPAD_DOWN)||y > 10000;
        c.a=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_A)!=0;
        c.b=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_B)!=0;
        c.x=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_X)!=0;
        c.y=SDL_GameControllerGetButton(g_controller,SDL_CONTROLLER_BUTTON_Y)!=0;
    } else if (g_joystick) {
        SDL_JoystickUpdate(); c.connected=SDL_JoystickGetAttached(g_joystick)==SDL_TRUE;
        const int axes=SDL_JoystickNumAxes(g_joystick), buttons=SDL_JoystickNumButtons(g_joystick), hats=SDL_JoystickNumHats(g_joystick);
        const Sint16 x=axes>0?SDL_JoystickGetAxis(g_joystick,0):0, y=axes>1?SDL_JoystickGetAxis(g_joystick,1):0;
        const Uint8 hat=hats>0?SDL_JoystickGetHat(g_joystick,0):SDL_HAT_CENTERED;
        c.left=x < -10000||(hat&SDL_HAT_LEFT)!=0; c.right=x > 10000||(hat&SDL_HAT_RIGHT)!=0;
        c.up=y < -10000||(hat&SDL_HAT_UP)!=0; c.down=y > 10000||(hat&SDL_HAT_DOWN)!=0;
        c.a=buttons>0&&SDL_JoystickGetButton(g_joystick,0)!=0; c.b=buttons>1&&SDL_JoystickGetButton(g_joystick,1)!=0;
        c.x=buttons>2&&SDL_JoystickGetButton(g_joystick,2)!=0; c.y=buttons>3&&SDL_JoystickGetButton(g_joystick,3)!=0;
    }
    c.up|=g_key_up; c.down|=g_key_down; c.left|=g_key_left; c.right|=g_key_right; c.a|=g_key_a; c.b|=g_key_b;
    return c;
}

void ThreadMarker(void* opaque) { static_cast<std::atomic<int>*>(opaque)->store(73); }

bool RunBootstrapChecks() {
    char* private_root=SDL_GetPrefPath("TwinsanityEvolved","NativeEESmoke");
    if (!private_root) return false;
    g_nativeee_initialized=ps2r::platform::android::Initialize(private_root);
    std::string root(private_root);
    if (!root.empty() && root.back()!='/') root.push_back('/');
    SDL_free(private_root);
    g_checks[0].passed=g_nativeee_initialized;
    if (!g_nativeee_initialized) return false;

    const auto& p=ps2r::platform::Current();
    const uint64_t t0=p.now_ns(); SDL_Delay(2); const uint64_t t1=p.now_ns();
    g_checks[1].passed=t0!=0 && t1>t0;

    const std::string log_path=root+"nativeee-smoke.log";
    auto& logger=ps2r::log::Logger::Instance();
    logger.Start(log_path,ps2r::log::Level::Info,true);
    const uint64_t before=logger.LinesWritten();
    logger.Logf(ps2r::log::Category::HOST,ps2r::log::Level::Info,"NativeEE Android logger is running");
    logger.Flush();
    auto log_file=p.open("nativeee-smoke.log",ps2r::platform::FileMode::Read);
    char line[256]{};
    g_checks[2].passed=logger.LinesWritten()==before+1 && log_file.fp && std::fgets(line,sizeof line,log_file.fp) &&
        std::strstr(line,"NativeEE Android logger is running")!=nullptr;
    if (log_file.fp) std::fclose(log_file.fp);

    std::atomic<int> marker{0};
    auto thread=p.thread_create(ThreadMarker,&marker,ps2r::platform::ThreadDesc{"nativeee-test",false,0});
    if (thread.handle) p.thread_join(thread);
    g_checks[3].passed=thread.handle!=nullptr && marker.load()==73;

    auto out=p.open("nativeee-file-test.txt",ps2r::platform::FileMode::Write);
    const char expected[]="NativeEE private file round trip";
    g_checks[4].passed=out.fp && std::fwrite(expected,1,sizeof(expected),out.fp)==sizeof(expected);
    if (out.fp) std::fclose(out.fp);
    auto in=p.open("nativeee-file-test.txt",ps2r::platform::FileMode::Read);
    char actual[64]{};
    g_checks[5].passed=g_checks[4].passed && in.fp && std::fread(actual,1,sizeof(expected),in.fp)==sizeof(expected) &&
        std::memcmp(actual,expected,sizeof(expected))==0;
    if (in.fp) std::fclose(in.fp);
    SDL_Log("NativeEE Android bootstrap checks completed");
    return true;
}

void StopBootstrap() {
    if (g_nativeee_initialized) {
        ps2r::log::Logger::Instance().Stop();
        ps2r::platform::android::Shutdown();
        g_nativeee_initialized=false;
    }
}

}  // namespace

int main(int, char**) {
    if (SDL_Init(SDL_INIT_VIDEO|SDL_INIT_GAMECONTROLLER|SDL_INIT_JOYSTICK|SDL_INIT_EVENTS)!=0) {
        SDL_Log("SDL_Init failed: %s",SDL_GetError()); return 1;
    }
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS,"0");
    SDL_GameControllerEventState(SDL_ENABLE);
    SDL_Window* window=SDL_CreateWindow("NativeEE Android Smoke",SDL_WINDOWPOS_UNDEFINED,SDL_WINDOWPOS_UNDEFINED,
        kWidth,kHeight,SDL_WINDOW_SHOWN|SDL_WINDOW_ALLOW_HIGHDPI);
    if (!window) { SDL_Log("SDL_CreateWindow failed: %s",SDL_GetError()); SDL_Quit(); return 1; }
    g_renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_ACCELERATED|SDL_RENDERER_PRESENTVSYNC);
    if (!g_renderer) g_renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_SOFTWARE);
    if (!g_renderer) { SDL_Log("SDL_CreateRenderer failed: %s",SDL_GetError()); SDL_DestroyWindow(window); SDL_Quit(); return 1; }
    SDL_RenderSetLogicalSize(g_renderer,kWidth,kHeight);
    (void)RunBootstrapChecks();
    OpenFirstController();

    bool running=true, paused=false;
    while (running) {
        SDL_Event event;
        if (SDL_WaitEventTimeout(&event,paused?250:16)==1) HandleEvent(event,running,paused);
        while (SDL_PollEvent(&event)==1) HandleEvent(event,running,paused);
        if (!running) break;
        if (!paused) DrawFrame(ReadControls(),false);
    }
    CloseController();
    StopBootstrap();
    SDL_DestroyRenderer(g_renderer); SDL_DestroyWindow(window); SDL_Quit();
    return 0;
}
