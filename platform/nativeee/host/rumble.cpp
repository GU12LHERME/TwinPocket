// platform/nativeee (host side) -- te_rumble.h: the game's own vibration played on the player's controller. Android only
// (the other lanes keep the accepted-and-silent output they had: host/pads.cpp).
//
// WHERE IT PLAYS (Android, SDL 2.30.9 -- checked against its sources):
//   1. a controller SDL drives itself (its HIDAPI drivers: SDL_GameControllerHasRumble) -- SDL_GameControllerRumble, the
//      two motors apart (the big one low frequency, the small one high);
//   2. else the controller's own vibrator as Android exposes it (InputDevice.getVibrator: SDL's haptic device of the same
//      name) -- SDL's Android joysticks answer SDL_GameControllerRumble with "unsupported" (ANDROID_JoystickRumble), so
//      a built-in or Bluetooth pad that vibrates through Android is reached this way;
//   3. else, in mode auto, the device's own vibrator (SDL's haptic "VIBRATOR_SERVICE") -- on a handheld the controls are
//      the device. Mode controller never uses it.
// Through Android's vibrators one motor plays what the two ask for (SDL's Android haptic: 0.6 x big + 0.4 x small); the
// RP6's built-in controls show Android one vibrator at most (one or two motors behind it: the device test says).
//
// Never two of them at once (no doubled vibration). Bounded effects renewed while the game's motors run (te_rumble.h Core).
//
// THE LOG: one line when a device is chosen (its name, the API) or none is found, one for a failure; with TE_RUMBLE_LOG=1
// (the launcher's diagnostic mode) each of the game's changes too: the motors asked for, how long the last ones ran.
#include "te_rumble.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "te_input.h"
#include "te_services.h"

#if TE_HAVE_SDL_INPUT && defined(__ANDROID__)
#include <SDL.h>
#define TE_RUMBLE_ON 1
#else
#define TE_RUMBLE_ON 0
#endif

namespace te::rumble {

#if TE_RUMBLE_ON
namespace {

enum class Mode { Auto, Controller, Off };
enum class Api { None, GameController, Haptic };

struct State {
    bool installed = false;
    Mode mode = Mode::Auto;
    Core core;
    // the device in use: chosen at the first effect, again after a controller came or went
    bool chosen = false;
    Api api = Api::None;
    SDL_GameController* controllers[4] = {};
    int controller_count = 0;
    SDL_Haptic* haptic = nullptr;
    int effect = -1;
    std::string device;
    bool said_none = false;
    bool log = false;
    uint64_t asked_at = 0;   // when the motors last went from stopped to running (the log's length)
    uint64_t commands = 0, failures = 0;
} g;

uint64_t NowMs() { return SDL_GetTicks64(); }

void CloseDevice() {
    if (g.haptic) {
        if (g.effect >= 0) {
            SDL_HapticStopEffect(g.haptic, g.effect);
            SDL_HapticDestroyEffect(g.haptic, g.effect);
        }
        SDL_HapticClose(g.haptic);
    }
    for (int i = 0; i < g.controller_count; ++i)
        if (g.controllers[i] && SDL_GameControllerGetAttached(g.controllers[i])) SDL_GameControllerRumble(g.controllers[i], 0, 0, 0);
    g.haptic = nullptr;
    g.effect = -1;
    g.controller_count = 0;
    g.api = Api::None;
    g.chosen = false;
}

bool OpenHaptic(int index, const char* why) {
    SDL_Haptic* h = SDL_HapticOpen(index);
    if (!h) {
        g.failures++;
        std::fprintf(stderr, "[twinsanityevolved] rumble: haptic '%s' did not open (%s)\n", SDL_HapticName(index), SDL_GetError());
        return false;
    }
    if ((SDL_HapticQuery(h) & SDL_HAPTIC_LEFTRIGHT) == 0) {
        std::fprintf(stderr, "[twinsanityevolved] rumble: haptic '%s' has no left/right effect\n", SDL_HapticName(index));
        SDL_HapticClose(h);
        return false;
    }
    SDL_HapticEffect e;
    std::memset(&e, 0, sizeof e);
    e.type = SDL_HAPTIC_LEFTRIGHT;
    e.leftright.length = Core::kLengthMs;
    const int id = SDL_HapticNewEffect(h, &e);
    if (id < 0) {
        g.failures++;
        std::fprintf(stderr, "[twinsanityevolved] rumble: haptic '%s' refused the effect (%s)\n", SDL_HapticName(index), SDL_GetError());
        SDL_HapticClose(h);
        return false;
    }
    g.haptic = h;
    g.effect = id;
    g.api = Api::Haptic;
    g.device = SDL_HapticName(index) ? SDL_HapticName(index) : "?";
    std::fprintf(stderr, "[twinsanityevolved] rumble: the game's vibration on '%s' (SDL haptic, Android vibrator: %s)\n",
                 g.device.c_str(), why);
    return true;
}

// The device for the game's motors (see the top of the file)
void Choose() {
    if (g.chosen) return;
    g.chosen = true;
    void* open[4];
    int count = 0;
    te::input::OpenControllers(open, &count, 4);
    // 1. controllers SDL drives itself, with motors
    g.controller_count = 0;
    std::string names;
    for (int i = 0; i < count; ++i) {
        auto* gc = static_cast<SDL_GameController*>(open[i]);
        if (!gc || !SDL_GameControllerGetAttached(gc)) continue;
        if (SDL_GameControllerHasRumble(gc)) {
            g.controllers[g.controller_count++] = gc;
            names += std::string(names.empty() ? "" : ", ") + (SDL_GameControllerName(gc) ? SDL_GameControllerName(gc) : "?");
        }
    }
    if (g.controller_count > 0) {
        g.api = Api::GameController;
        g.device = names;
        std::fprintf(stderr, "[twinsanityevolved] rumble: the game's vibration on '%s' (SDL_GameControllerRumble: two motors)\n", names.c_str());
        return;
    }
    // 2 / 3. Android's vibrators through SDL's haptic devices
    if (!SDL_WasInit(SDL_INIT_HAPTIC) && SDL_InitSubSystem(SDL_INIT_HAPTIC) != 0) {
        g.failures++;
        std::fprintf(stderr, "[twinsanityevolved] rumble: SDL haptic unavailable (%s): no vibration\n", SDL_GetError());
        return;
    }
    const int haptics = SDL_NumHaptics();
    for (int i = 0; i < count; ++i) {
        auto* gc = static_cast<SDL_GameController*>(open[i]);
        const char* name = gc ? SDL_GameControllerName(gc) : nullptr;
        SDL_Joystick* js = gc ? SDL_GameControllerGetJoystick(gc) : nullptr;
        const char* jname = js ? SDL_JoystickName(js) : nullptr;
        for (int h = 0; h < haptics; ++h) {
            const char* hn = SDL_HapticName(h);
            if (!hn || std::strcmp(hn, "VIBRATOR_SERVICE") == 0) continue;
            if ((name && std::strcmp(hn, name) == 0) || (jname && std::strcmp(hn, jname) == 0))
                if (OpenHaptic(h, "the controller's own")) return;
        }
    }
    if (g.mode == Mode::Auto) {
        for (int h = 0; h < haptics; ++h) {
            const char* hn = SDL_HapticName(h);
            if (hn && std::strcmp(hn, "VIBRATOR_SERVICE") == 0 && OpenHaptic(h, "the device's own -- the controller shows none")) return;
        }
    }
    if (!g.said_none) {
        g.said_none = true;
        std::fprintf(stderr, "[twinsanityevolved] rumble: no motor reachable (%d controller(s), %d haptic device(s), mode %s): the game's "
                             "vibration is not played\n", count, haptics, g.mode == Mode::Auto ? "auto" : "controller");
    }
}

void Apply(const Command& c) {
    if (c.kind == Command::None) return;
    if (c.kind == Command::Play) Choose();
    switch (g.api) {
    case Api::GameController:
        for (int i = 0; i < g.controller_count; ++i) {
            SDL_GameController* gc = g.controllers[i];
            if (!gc || !SDL_GameControllerGetAttached(gc)) continue;
            const bool play = c.kind == Command::Play;
            if (SDL_GameControllerRumble(gc, play ? c.low : 0, play ? c.high : 0, play ? c.length_ms : 0) != 0 && play) {
                if (g.failures++ < 3) std::fprintf(stderr, "[twinsanityevolved] rumble: SDL_GameControllerRumble failed (%s)\n", SDL_GetError());
            }
        }
        break;
    case Api::Haptic:
        if (!g.haptic || g.effect < 0) break;
        if (c.kind == Command::Play) {
            SDL_HapticEffect e;
            std::memset(&e, 0, sizeof e);
            e.type = SDL_HAPTIC_LEFTRIGHT;
            e.leftright.length = c.length_ms;
            e.leftright.large_magnitude = uint16_t(c.low >> 1);    // SDL's magnitudes are 0..32767
            e.leftright.small_magnitude = uint16_t(c.high >> 1);
            if (SDL_HapticUpdateEffect(g.haptic, g.effect, &e) != 0 || SDL_HapticRunEffect(g.haptic, g.effect, 1) != 0) {
                if (g.failures++ < 3) std::fprintf(stderr, "[twinsanityevolved] rumble: the haptic effect failed (%s)\n", SDL_GetError());
            }
        } else {
            SDL_HapticStopEffect(g.haptic, g.effect);
        }
        break;
    case Api::None: break;
    }
}

// The game's motor output (title::Input's MotorOutput: the game thread). Accepted whatever the device does: the game's
// own logic never depends on the motors (as before, when nothing was installed).
bool Output(unsigned port, unsigned slot, uint8_t small, uint8_t big) {
    if (port != 0 || slot != 0 || g.mode == Mode::Off) return true;
    Motors m;
    m.small = small != 0;
    m.big = big;
    const uint64_t now = NowMs();
    const Motors before = g.core.Wanted();
    const Command c = g.core.Set(m, now);
    if (g.log && !(m == before)) {
        g.commands++;
        if (m.Any() && !before.Any()) g.asked_at = now;
        std::fprintf(stderr, "[twinsanityevolved] rumble: game motors small %d big %u%s%s (device '%s')\n", m.small ? 1 : 0, unsigned(m.big),
                     !m.Any() && before.Any() ? (" -- ran " + std::to_string(now - g.asked_at) + " ms").c_str() : "",
                     g.core.Suspended() ? " [suspended: not played]" : "", g.device.c_str());
    }
    Apply(c);
    return true;
}

}  // namespace

void Install(te::Pads& pads, const char* mode) {
    if (mode && std::strcmp(mode, "off") == 0) g.mode = Mode::Off;
    else if (mode && std::strcmp(mode, "controller") == 0) g.mode = Mode::Controller;
    else g.mode = Mode::Auto;
    const char* log = std::getenv("TE_RUMBLE_LOG");
    g.log = log && log[0] == '1';
    pads.SetMotorOutput(&Output);
    g.installed = true;
    std::fprintf(stderr, "[twinsanityevolved] rumble: the game's motors connected (mode %s%s)\n",
                 g.mode == Mode::Off ? "off" : g.mode == Mode::Controller ? "controller" : "auto", g.log ? ", logged" : "");
}

void OnEvent(const void* event) {
    if (!g.installed || !event) return;
    const SDL_Event& e = *static_cast<const SDL_Event*>(event);
    switch (e.type) {
    case SDL_APP_WILLENTERBACKGROUND:
    case SDL_APP_DIDENTERBACKGROUND:
    case SDL_APP_TERMINATING:
        Apply(g.core.Suspend());
        break;
    case SDL_APP_DIDENTERFOREGROUND:
        g.core.Resume();
        break;
    case SDL_WINDOWEVENT:
        if (e.window.event == SDL_WINDOWEVENT_FOCUS_LOST || e.window.event == SDL_WINDOWEVENT_MINIMIZED) Apply(g.core.Suspend());
        else if (e.window.event == SDL_WINDOWEVENT_FOCUS_GAINED || e.window.event == SDL_WINDOWEVENT_RESTORED) g.core.Resume();
        break;
    case SDL_CONTROLLERDEVICEADDED:
    case SDL_CONTROLLERDEVICEREMOVED:
        // the controllers changed: what plays stops, the device is chosen again at the next effect
        Apply(g.core.Suspend());
        CloseDevice();
        g.said_none = false;
        g.core.Resume();
        if (g.log) std::fprintf(stderr, "[twinsanityevolved] rumble: controller %s: stopped, the device chosen again at the next vibration\n",
                                e.type == SDL_CONTROLLERDEVICEADDED ? "added" : "removed");
        break;
    default: break;
    }
}

void Tick() {
    if (!g.installed) return;
    Apply(g.core.Tick(NowMs()));
}

void Shutdown() {
    if (!g.installed) return;
    Apply(g.core.Suspend());
    CloseDevice();
    if (g.log) std::fprintf(stderr, "[twinsanityevolved] rumble: stopped at shutdown (%llu change(s), %llu failure(s))\n",
                            (unsigned long long)g.commands, (unsigned long long)g.failures);
    g.installed = false;
}

#else   // not Android: the accepted-and-silent output of host/pads.cpp stays

void Install(te::Pads&, const char*) {}
void OnEvent(const void*) {}
void Tick() {}
void Shutdown() {}

#endif

}  // namespace te::rumble
