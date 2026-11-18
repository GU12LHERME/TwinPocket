#include <SDL.h>

#include <array>
#include <cstdint>

#if !defined(__ANDROID__) || !defined(__aarch64__)
#error "This APK smoke app must target Android arm64-v8a"
#endif

#if !defined(__ANDROID_API__) || __ANDROID_API__ < 24
#error "This APK smoke app requires Android API 24 or newer"
#endif

namespace {

constexpr int kWidth = 960;
constexpr int kHeight = 540;

struct Controls {
    bool up = false;
    bool down = false;
    bool left = false;
    bool right = false;
    bool a = false;
    bool b = false;
    bool x = false;
    bool y = false;
    bool connected = false;
};

struct Glyph {
    char character;
    std::array<std::uint8_t, 7> rows;
};

constexpr Glyph kFont[] = {
    {' ', {0, 0, 0, 0, 0, 0, 0}},
    {'2', {14, 17, 1, 6, 8, 16, 31}},
    {'4', {2, 6, 10, 18, 31, 2, 2}},
    {'6', {6, 8, 16, 30, 17, 17, 14}},
    {'A', {14, 17, 17, 31, 17, 17, 17}},
    {'B', {30, 17, 17, 30, 17, 17, 30}},
    {'D', {30, 17, 17, 17, 17, 17, 30}},
    {'E', {31, 16, 16, 30, 16, 16, 31}},
    {'I', {31, 4, 4, 4, 4, 4, 31}},
    {'L', {16, 16, 16, 16, 16, 16, 31}},
    {'M', {17, 27, 21, 21, 17, 17, 17}},
    {'N', {17, 25, 21, 19, 17, 17, 17}},
    {'O', {14, 17, 17, 17, 17, 17, 14}},
    {'P', {30, 17, 17, 30, 16, 16, 16}},
    {'R', {30, 17, 17, 30, 20, 18, 17}},
    {'S', {15, 16, 16, 14, 1, 1, 30}},
    {'T', {31, 4, 4, 4, 4, 4, 4}},
    {'X', {17, 17, 10, 4, 10, 17, 17}},
    {'Y', {17, 17, 10, 4, 4, 4, 4}},
};

SDL_GameController* g_controller = nullptr;
SDL_Joystick* g_joystick = nullptr;
SDL_JoystickID g_device_id = -1;
SDL_Renderer* g_renderer = nullptr;
bool g_key_up = false;
bool g_key_down = false;
bool g_key_left = false;
bool g_key_right = false;
bool g_key_a = false;
bool g_key_b = false;

const Glyph* FindGlyph(char character) {
    for (const Glyph& glyph : kFont) {
        if (glyph.character == character) return &glyph;
    }
    return nullptr;
}

void DrawText(const char* text, int x, int y, int scale, SDL_Color color) {
    SDL_SetRenderDrawColor(g_renderer, color.r, color.g, color.b, color.a);
    for (const char* cursor = text; *cursor; ++cursor, x += 6 * scale) {
        const Glyph* glyph = FindGlyph(*cursor);
        if (!glyph) continue;
        for (int row = 0; row < 7; ++row) {
            for (int column = 0; column < 5; ++column) {
                if ((glyph->rows[static_cast<std::size_t>(row)] & (1u << (4 - column))) == 0) continue;
                const SDL_Rect pixel{x + column * scale, y + row * scale, scale, scale};
                SDL_RenderFillRect(g_renderer, &pixel);
            }
        }
    }
}

void SetColor(SDL_Color color) {
    SDL_SetRenderDrawColor(g_renderer, color.r, color.g, color.b, color.a);
}

void FillRect(int x, int y, int width, int height, SDL_Color color) {
    SetColor(color);
    const SDL_Rect rectangle{x, y, width, height};
    SDL_RenderFillRect(g_renderer, &rectangle);
}

void FillCircle(int center_x, int center_y, int radius, SDL_Color color) {
    SetColor(color);
    for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
            if (x * x + y * y <= radius * radius) {
                const SDL_Rect pixel{center_x + x, center_y + y, 1, 1};
                SDL_RenderFillRect(g_renderer, &pixel);
            }
        }
    }
}

void DrawDpad(const Controls& controls) {
    const SDL_Color off{49, 61, 87, 255};
    const SDL_Color on{69, 211, 190, 255};
    FillRect(205, 292, 170, 58, off);
    FillRect(261, 236, 58, 170, off);
    if (controls.left) FillRect(205, 292, 56, 58, on);
    if (controls.right) FillRect(319, 292, 56, 58, on);
    if (controls.up) FillRect(261, 236, 58, 56, on);
    if (controls.down) FillRect(261, 350, 58, 56, on);
    FillRect(268, 299, 44, 44, SDL_Color{28, 37, 57, 255});
}

void DrawFaceButton(int x, int y, const char* label, bool pressed, SDL_Color tint) {
    const SDL_Color color = pressed ? SDL_Color{246, 237, 206, 255} : tint;
    FillCircle(x, y, 31, color);
    DrawText(label, x - 6, y - 10, 2, SDL_Color{19, 26, 43, 255});
}

void DrawFrame(const Controls& controls, bool paused) {
    SetColor(SDL_Color{16, 23, 42, 255});
    SDL_RenderClear(g_renderer);
    FillRect(0, 0, kWidth, 118, paused ? SDL_Color{125, 75, 38, 255} : SDL_Color{25, 83, 104, 255});
    DrawText("SDL2 ARM64", 54, 35, 6, SDL_Color{238, 244, 250, 255});
    DrawText("PAD INPUT TEST", 59, 474, 3, SDL_Color{141, 164, 193, 255});

    const SDL_Color status = controls.connected ? SDL_Color{69, 211, 190, 255} : SDL_Color{115, 129, 154, 255};
    FillCircle(884, 58, 13, status);
    FillRect(830, 89, controls.connected ? 72 : 34, 6, status);

    DrawDpad(controls);
    DrawFaceButton(685, 286, "Y", controls.y, SDL_Color{202, 94, 108, 255});
    DrawFaceButton(743, 344, "B", controls.b, SDL_Color{212, 96, 92, 255});
    DrawFaceButton(627, 344, "X", controls.x, SDL_Color{77, 146, 222, 255});
    DrawFaceButton(685, 402, "A", controls.a, SDL_Color{72, 189, 130, 255});
    SDL_RenderPresent(g_renderer);
}

void CloseController() {
    if (g_controller) SDL_GameControllerClose(g_controller);
    if (g_joystick) SDL_JoystickClose(g_joystick);
    g_controller = nullptr;
    g_joystick = nullptr;
    g_device_id = -1;
}

void OpenController(int device_index) {
    if (g_controller || g_joystick || device_index < 0) return;
    if (SDL_IsGameController(device_index)) {
        g_controller = SDL_GameControllerOpen(device_index);
        if (g_controller) {
            g_device_id = SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(g_controller));
            return;
        }
    }
    g_joystick = SDL_JoystickOpen(device_index);
    if (g_joystick) g_device_id = SDL_JoystickInstanceID(g_joystick);
}

void OpenFirstController() {
    for (int index = 0; index < SDL_NumJoysticks(); ++index) {
        OpenController(index);
        if (g_controller || g_joystick) return;
    }
}

void SetKey(SDL_Keycode key, bool pressed) {
    switch (key) {
        case SDLK_UP: case SDLK_w: g_key_up = pressed; break;
        case SDLK_DOWN: case SDLK_s: g_key_down = pressed; break;
        case SDLK_LEFT: case SDLK_a: g_key_left = pressed; break;
        case SDLK_RIGHT: case SDLK_d: g_key_right = pressed; break;
        case SDLK_SPACE: case SDLK_z: g_key_a = pressed; break;
        case SDLK_LCTRL: case SDLK_x: g_key_b = pressed; break;
        default: break;
    }
}

void HandleEvent(const SDL_Event& event, bool& running, bool& paused) {
    switch (event.type) {
        case SDL_QUIT:
        case SDL_APP_TERMINATING:
            running = false;
            break;
        case SDL_APP_WILLENTERBACKGROUND:
            paused = true;
            break;
        case SDL_APP_DIDENTERFOREGROUND:
            paused = false;
            break;
        case SDL_WINDOWEVENT:
            if (event.window.event == SDL_WINDOWEVENT_MINIMIZED || event.window.event == SDL_WINDOWEVENT_HIDDEN) paused = true;
            if (event.window.event == SDL_WINDOWEVENT_RESTORED || event.window.event == SDL_WINDOWEVENT_SHOWN) paused = false;
            break;
        case SDL_KEYDOWN:
            if (event.key.keysym.sym == SDLK_ESCAPE || event.key.keysym.sym == SDLK_AC_BACK) running = false;
            else SetKey(event.key.keysym.sym, true);
            break;
        case SDL_KEYUP:
            SetKey(event.key.keysym.sym, false);
            break;
        case SDL_CONTROLLERDEVICEADDED:
            OpenController(event.cdevice.which);
            break;
        case SDL_JOYDEVICEADDED:
            OpenController(event.jdevice.which);
            break;
        case SDL_CONTROLLERDEVICEREMOVED:
            if (event.cdevice.which == g_device_id) {
                CloseController();
                OpenFirstController();
            }
            break;
        case SDL_JOYDEVICEREMOVED:
            if (event.jdevice.which == g_device_id) {
                CloseController();
                OpenFirstController();
            }
            break;
        default:
            break;
    }
}

Controls ReadControls() {
    Controls controls;
    if (g_controller) {
        SDL_GameControllerUpdate();
        controls.connected = SDL_GameControllerGetAttached(g_controller) == SDL_TRUE;
        const int left_x = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_LEFTX);
        const int left_y = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_LEFTY);
        controls.left = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_DPAD_LEFT) || left_x < -10000;
        controls.right = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_DPAD_RIGHT) || left_x > 10000;
        controls.up = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_DPAD_UP) || left_y < -10000;
        controls.down = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_DPAD_DOWN) || left_y > 10000;
        controls.a = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_A) != 0;
        controls.b = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_B) != 0;
        controls.x = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_X) != 0;
        controls.y = SDL_GameControllerGetButton(g_controller, SDL_CONTROLLER_BUTTON_Y) != 0;
    } else if (g_joystick) {
        SDL_JoystickUpdate();
        controls.connected = SDL_JoystickGetAttached(g_joystick) == SDL_TRUE;
        const int axes = SDL_JoystickNumAxes(g_joystick);
        const int buttons = SDL_JoystickNumButtons(g_joystick);
        const int hats = SDL_JoystickNumHats(g_joystick);
        const Sint16 horizontal = axes > 0 ? SDL_JoystickGetAxis(g_joystick, 0) : 0;
        const Sint16 vertical = axes > 1 ? SDL_JoystickGetAxis(g_joystick, 1) : 0;
        const Uint8 hat = hats > 0 ? SDL_JoystickGetHat(g_joystick, 0) : SDL_HAT_CENTERED;
        controls.left = horizontal < -10000 || (hat & SDL_HAT_LEFT) != 0;
        controls.right = horizontal > 10000 || (hat & SDL_HAT_RIGHT) != 0;
        controls.up = vertical < -10000 || (hat & SDL_HAT_UP) != 0;
        controls.down = vertical > 10000 || (hat & SDL_HAT_DOWN) != 0;
        controls.a = buttons > 0 && SDL_JoystickGetButton(g_joystick, 0) != 0;
        controls.b = buttons > 1 && SDL_JoystickGetButton(g_joystick, 1) != 0;
        controls.x = buttons > 2 && SDL_JoystickGetButton(g_joystick, 2) != 0;
        controls.y = buttons > 3 && SDL_JoystickGetButton(g_joystick, 3) != 0;
    }
    controls.up = controls.up || g_key_up;
    controls.down = controls.down || g_key_down;
    controls.left = controls.left || g_key_left;
    controls.right = controls.right || g_key_right;
    controls.a = controls.a || g_key_a;
    controls.b = controls.b || g_key_b;
    return controls;
}

}  // namespace

int main(int, char**) {
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_JOYSTICK | SDL_INIT_EVENTS) != 0) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "0");
    SDL_GameControllerEventState(SDL_ENABLE);

    SDL_Window* window = SDL_CreateWindow("Twinsanity SDL2 Smoke", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
                                          kWidth, kHeight, SDL_WINDOW_SHOWN | SDL_WINDOW_ALLOW_HIGHDPI);
    if (!window) {
        SDL_Log("SDL_CreateWindow failed: %s", SDL_GetError());
        SDL_Quit();
        return 1;
    }
    g_renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!g_renderer) g_renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    if (!g_renderer) {
        SDL_Log("SDL_CreateRenderer failed: %s", SDL_GetError());
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }
    SDL_RenderSetLogicalSize(g_renderer, kWidth, kHeight);
    OpenFirstController();

    bool running = true;
    bool paused = false;
    while (running) {
        SDL_Event event;
        if (SDL_WaitEventTimeout(&event, paused ? 250 : 16) == 1) HandleEvent(event, running, paused);
        while (SDL_PollEvent(&event) == 1) HandleEvent(event, running, paused);
        if (!running) break;
        if (!paused) DrawFrame(ReadControls(), false);
    }

    CloseController();
    SDL_DestroyRenderer(g_renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}
