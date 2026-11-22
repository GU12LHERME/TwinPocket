// te_input.h -- the pads' host sampler over the engine's SDL InputSystem (host/te_input.cpp). The window owner
// (graphics) coordinates the SDL event pump through SetPumpOwned/HandleSdlEvent.
#pragma once
#include <cstdint>
#include <string>

#include "te_host.h"

union SDL_Event;

namespace te {
class Pads;
namespace input {

// Installs the SDL sampler on `pads` (pad 0). False when SDL input is off or unavailable: the neutral pad stays.
bool Install(Pads& pads, const Settings& settings);
// True (default): this module polls SDL events at the boundary. The window owner sets false and forwards events.
void SetPumpOwned(bool owned);
bool PumpOwned();
// The pads' sampler pumps SDL's events itself at every boundary its pad is sampled (installed, and the pump its own)
bool Pumping();
#if TE_HAVE_SDL_INPUT
void HandleSdlEvent(const SDL_Event& event);
#endif
// "kbm=on|off,gamepad=connected|absent,active=..." (the engine's census string), "none" without SDL input.
std::string Sources();
// The SDL_GameController* of every controller the input system has open (rumble.cpp: the game's motors); empty without SDL input
void OpenControllers(void** out, int* count, int max);
// The game's vibration on Android (ps2r.ini android.rumble: auto | controller | off), before Install
void SetRumbleMode(const std::string& mode);
// The keyboard harness ("menus navigable with the keyboard" check without an OS event injector): the SDL
// key NAME ("Y", "Down", "Space"...) reads as HELD through the engine's KeyboardMouseBackend::InjectKey -- its bindings
// table and InputMapper, the path a real key takes after SDL_GetKeyboardState -- at every boundary in [from, to].
// A window plays once: after a state is loaded in place (the timeline goes back) a delivered window is spent.
// False when the name is not an SDL key. Takes effect once Install has run (the entries are kept until then).
bool InjectKey(const std::string& key_name, uint64_t from, uint64_t to, std::string* error);
// The speedrun mod's keys: whether the SDL key of that NAME ("F9") is held right now, by SDL's keyboard state (the pump
// keeps it current); false without SDL input or for an unknown name. A harness --inject-key of the name reads as held too.
bool KeyHeld(const char* key_name);
// The window-close harness: an SDL_QUIT in the event queue (what the window's close button posts); the window owner's
// pump (ps2e_draw_pump) takes it at the next boundary and the runner exits 0 through te::RequestExit.
bool RequestWindowClose();
// The sticks' response (the engine's GamepadBackend: the radial deadzone, 0..0.9, and the per-axis sensitivity, 0.5..2):
// applied at once when the InputSystem exists, else kept for Install. A negative value keeps the engine's own (0.15, 1.33).
void SetStickResponse(float deadzone, float sensitivity);
// The gamepad-button remap as the engine's bindings grammar's [pad] section ("[pad]\ncross = a\n..."; the rows not named
// keep SDL's standard layout, the DualShock 2's): parsed by the engine's InputMapper (a bad name: the whole section refused,
// the standard layout kept, one log line) and handed to the GamepadBackend at Install. "" = the standard layout.
void SetPadMap(const std::string& pad_section);
void Shutdown();

}  // namespace input
}  // namespace te

// The Vita's window pump (graphics PumpWindow, every vertical blank): nonzero when it may be left out -- the sampler pumps
// (its SDL_PollEvent loop drains the queue and ends the run on a quit) and the screen has no size or mode to follow.
// Also the power tick that keeps the device awake, once a second (SDL's at every pump is off: te_vita.cpp). TE_VITA_PUMP=1:
// never left out. Elsewhere 0.
extern "C" int te_input_vita_window_pump_skipped(void);
