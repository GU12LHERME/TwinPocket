// platform/sdl/input/gamepad_backend.cpp
#include "engine/platform/sdl/input/gamepad_backend.h"

#include <SDL.h>

#include <chrono>
#include <cmath>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "engine/platform/sdl/input/gamepad_convert.h"

namespace ps2r::platform::sdl {

namespace {

std::string GuidOfJoystick(SDL_Joystick* js) {
    char buf[64] = {0};
    SDL_JoystickGetGUIDString(SDL_JoystickGetGUID(js), buf, sizeof(buf));
    return std::string(buf);
}

// Larger magnitude wins; on a tie the current (earlier) value stays.
uint64_t SteadyNs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

// What the pad wire carries for a normalised stick value (input_mapper.cpp StickToByte: -1 -> 0x00, 0 -> 0x80, +1 -> 0xFF)
int WireByte(float v) {
    long b = std::lround(v * 127.5f + 127.5f);
    return b < 0 ? 0 : b > 255 ? 255 : static_cast<int>(b);
}

// A joystick that is not a game pad and must never be given the generic pad mapping below: SDL's own "Android
// Accelerometer" (SDL_HINT_ACCELEROMETER_AS_JOYSTICK, on by default on Android) and other sensors. SDL itself refuses
// them a controller mapping; mapped as a pad, the device's tilt is a stick held over (gravity on the accelerometer's Y
// axis = the left stick pushed down).
bool IsSensor(const char* name) {
    if (!name) return false;
    const size_t n = std::strlen(name);
    static const char* const kTails[] = {"Accelerometer", "Gyroscope", "Gyro", "Sensor"};
    for (const char* t : kTails) {
        const size_t k = std::strlen(t);
        if (n >= k && std::strcmp(name + n - k, t) == 0) return true;
    }
    return false;
}

float Pick(float cur, float cand) {
    const float ac = cur < 0 ? -cur : cur, aa = cand < 0 ? -cand : cand;
    return aa > ac ? cand : cur;
}

}  // namespace

GamepadBackend::GamepadBackend(float deadzone, float sensitivity, bool enabled,
                               const std::string& gamecontrollerdb_path)
    : enabled_(enabled) {
    SetDeadzone(deadzone);
    SetSensitivity(sensitivity);

    // Keep pads usable while the window is unfocused; must precede the
    // joystick subsystem init.
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
#if defined(__ANDROID__)
    // the accelerometer is not a pad (see IsSensor); set before the joystick subsystem starts
    SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");
    // the stick log (LogSticks: up to 8 lines a second while a stick moves) was on by default for the stick-drift diagnosis,
    // now fixed; it is the launcher's diagnostic mode's (TE_INPUT_LOG=1), off for play
#endif
    if (const char* v = std::getenv("TE_INPUT_LOG")) log_sticks_ = v[0] == '1';
    if (SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {
        std::fprintf(stderr, "input: gamepad subsystem init failed: %s\n", SDL_GetError());
        return;
    }
    subsystem_ok_ = true;

    if (!gamecontrollerdb_path.empty()) {
        const int n = SDL_GameControllerAddMappingsFromFile(gamecontrollerdb_path.c_str());
        if (n > 0) std::fprintf(stderr, "input: gamepad: %d mappings loaded from %s\n", n, gamecontrollerdb_path.c_str());
    }

    const int n = SDL_NumJoysticks();
    std::fprintf(stderr, "input: %d joystick device(s) at start-up\n", n);
    for (int i = 0; i < n; i++) TryOpenDeviceIndex(i);
    PublishSnapshot();
    if (pads_.empty()) std::fprintf(stderr, "input: gamepad: none detected at startup (hotplug supported)\n");
}

GamepadBackend::~GamepadBackend() {
    for (auto& p : pads_)
        if (p.controller) SDL_GameControllerClose((SDL_GameController*)p.controller);
    pads_.clear();
    snapshot_.clear();
    if (subsystem_ok_) SDL_QuitSubSystem(SDL_INIT_GAMECONTROLLER);
}

void GamepadBackend::SetSelection(int selection) {
    if (selection_ != selection) ClearPending();
    selection_ = selection;
    if (selection >= 0) {
        for (const auto& p : snapshot_)
            if (p.instance_id == selection) { preferred_guid_ = p.guid; return; }
    } else {
        preferred_guid_.clear();
    }
}

void GamepadBackend::ClearPending() {
    for (auto& pad : pads_) pad.pending_buttons = 0;
}

void GamepadBackend::SetEnabled(bool enabled) {
    if (enabled_ != enabled) ClearPending();
    enabled_ = enabled;
}

void GamepadBackend::SetUiCaptured(bool captured) {
    if (ui_captured_ != captured) ClearPending();
    ui_captured_ = captured;
}

void GamepadBackend::SetDeadzone(float dz) {
    if (dz < kMinDeadzone) dz = kMinDeadzone;
    if (dz > kMaxDeadzone) dz = kMaxDeadzone;
    deadzone_ = dz;
}

void GamepadBackend::SetSensitivity(float s) {
    if (s < kMinSensitivity) s = kMinSensitivity;
    if (s > kMaxSensitivity) s = kMaxSensitivity;
    sensitivity_ = s;
}

// Open device index i as a game controller. Without an SDL mapping, register
// a best-effort generic (standard Xbox-style) layout keyed on the joystick
// GUID first; a gamecontrollerdb entry overrides it when present.
void GamepadBackend::TryOpenDeviceIndex(int device_index) {
    const SDL_JoystickID iid = SDL_JoystickGetDeviceInstanceID(device_index);
    for (const auto& p : pads_)
        if (p.instance_id == (int)iid) return;   // JOYDEVICEADDED for a pad opened at startup

    {
        char guid[64] = {0};
        SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(device_index), guid, sizeof(guid));
        const char* jname = SDL_JoystickNameForIndex(device_index);
        std::fprintf(stderr, "input: device %d: '%s' guid %s vendor %04x product %04x, game controller: %s\n", device_index,
                     jname ? jname : "?", guid, SDL_JoystickGetDeviceVendor(device_index), SDL_JoystickGetDeviceProduct(device_index),
                     SDL_IsGameController(device_index) ? "yes" : "no");
        if (IsSensor(jname)) {
            std::fprintf(stderr, "input: device %d ('%s') is a sensor, not a pad: ignored\n", device_index, jname);
            return;
        }
    }
    bool generic = false;
    if (!SDL_IsGameController(device_index)) {
        char guid[64] = {0};
        SDL_JoystickGetGUIDString(SDL_JoystickGetDeviceGUID(device_index), guid, sizeof(guid));
        const char* jname = SDL_JoystickNameForIndex(device_index);
        const std::string mapping = std::string(guid) + "," + (jname ? jname : "Generic Controller") +
            ",a:b0,b:b1,x:b2,y:b3,back:b4,guide:b5,start:b6,"
            "leftstick:b7,rightstick:b8,leftshoulder:b9,rightshoulder:b10,"
            "dpup:b11,dpdown:b12,dpleft:b13,dpright:b14,"
            "leftx:a0,lefty:a1,rightx:a2,righty:a3,"
            "lefttrigger:a4,righttrigger:a5";
        if (SDL_GameControllerAddMapping(mapping.c_str()) < 0) {
            std::fprintf(stderr, "input: gamepad: no mapping for '%s' (%s) and the generic fallback failed: %s\n",
                         jname ? jname : "?", guid, SDL_GetError());
            return;
        }
        generic = true;
    }

    SDL_GameController* gc = SDL_GameControllerOpen(device_index);
    if (!gc) {
        std::fprintf(stderr, "input: gamepad: open failed for device %d: %s\n", device_index, SDL_GetError());
        return;
    }

    Pad pad;
    pad.controller = gc;
    pad.generic_mapping = generic;
    SDL_Joystick* js = SDL_GameControllerGetJoystick(gc);
    pad.instance_id = js ? (int)SDL_JoystickInstanceID(js) : -1;
    pads_.push_back(pad);

    const char* name = SDL_GameControllerName(gc);
    std::fprintf(stderr, "input: gamepad connected: %s (instance %d)%s\n", name ? name : "?", pad.instance_id,
                 generic ? " [generic mapping]" : "");
    if (js)
        std::fprintf(stderr, "input:   %d axes, %d buttons, %d hats; deadzone %.2f (radial), sensitivity %.2f\n", SDL_JoystickNumAxes(js),
                     SDL_JoystickNumButtons(js), SDL_JoystickNumHats(js), deadzone_, sensitivity_);
    if (char* mapping = SDL_GameControllerMapping(gc)) {
        std::fprintf(stderr, "input:   mapping: %s\n", mapping);
        SDL_free(mapping);
    }

    // Reconnect-by-GUID: a pad matching the persisted preference is selected
    // when the selection is Auto.
    if (js && !preferred_guid_.empty() && preferred_guid_ == GuidOfJoystick(js) && selection_ == kSelectionAuto) {
        selection_ = pad.instance_id;
        std::fprintf(stderr, "input: gamepad: preferred pad reconnected -> selected (instance %d)\n", pad.instance_id);
    }
}

void GamepadBackend::CloseInstance(int instance_id) {
    for (size_t i = 0; i < pads_.size(); i++) {
        if (pads_[i].instance_id != instance_id) continue;
        std::fprintf(stderr, "input: gamepad disconnected (instance %d)\n", instance_id);
        SDL_GameControllerClose((SDL_GameController*)pads_[i].controller);
        pads_.erase(pads_.begin() + (long)i);
        // The selected pad went away: fall back to Auto so input keeps
        // working; the preferred GUID stays armed for reconnection.
        if (selection_ == instance_id) selection_ = kSelectionAuto;
        return;
    }
}

void GamepadBackend::PublishSnapshot() {
    snapshot_.clear();
    snapshot_.reserve(pads_.size());
    for (auto& p : pads_) {
        PadInfo info;
        info.instance_id = p.instance_id;
        info.generic_mapping = p.generic_mapping;
        const char* n = SDL_GameControllerName((SDL_GameController*)p.controller);
        info.name = n ? n : "Unknown Controller";
        SDL_Joystick* js = SDL_GameControllerGetJoystick((SDL_GameController*)p.controller);
        if (js) info.guid = GuidOfJoystick(js);
        snapshot_.push_back(std::move(info));
    }
}

void GamepadBackend::HandleEvent(const SDL_Event& e) {
    if (e.type == SDL_JOYDEVICEADDED) {
        TryOpenDeviceIndex(e.jdevice.which);   // which = device INDEX
        PublishSnapshot();
    } else if (e.type == SDL_JOYDEVICEREMOVED) {
        CloseInstance((int)e.jdevice.which);   // which = INSTANCE id
        PublishSnapshot();
    } else if (e.type == SDL_WINDOWEVENT &&
               (e.window.event == SDL_WINDOWEVENT_FOCUS_LOST || e.window.event == SDL_WINDOWEVENT_FOCUS_GAINED)) {
        ClearPending();
    } else if (enabled_ && !ui_captured_ && selection_ != kSelectionNone &&
               (e.type == SDL_CONTROLLERBUTTONDOWN || e.type == SDL_CONTROLLERAXISMOTION)) {
        const int instance = e.type == SDL_CONTROLLERBUTTONDOWN ? e.cbutton.which : e.caxis.which;
        for (auto& pad : pads_) {
            if (pad.instance_id != instance || (selection_ >= 0 && pad.instance_id != selection_)) continue;
            GamepadRaw edge{};
            if (e.type == SDL_CONTROLLERBUTTONDOWN && e.cbutton.button < GamepadRaw::kButtons)
                edge.button[e.cbutton.button] = 1;
            else if (e.type == SDL_CONTROLLERAXISMOTION &&
                     (e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERLEFT || e.caxis.axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT))
                edge.axis[e.caxis.axis] = e.caxis.value;
            NormalizedInput converted;
            GamepadConvert::Convert(edge, deadzone_, converted, sensitivity_, map_);
            pad.pending_buttons |= converted.buttons;
        }
    }
}

void GamepadBackend::Poll(NormalizedInput& out) {
    if (!enabled_ || pads_.empty() || selection_ == kSelectionNone) return;
    for (auto& p : pads_) {
        if (selection_ >= 0 && p.instance_id != selection_) continue;
        SDL_GameController* gc = (SDL_GameController*)p.controller;

        GamepadRaw raw;
        if (!(raw_source_ && pads_.size() == 1 && raw_source_(raw_user_, raw))) {
            for (int a = 0; a < GamepadRaw::kAxes; a++)
                raw.axis[a] = SDL_GameControllerGetAxis(gc, (SDL_GameControllerAxis)a);
            for (int b = 0; b < GamepadRaw::kButtons; b++)
                raw.button[b] = SDL_GameControllerGetButton(gc, (SDL_GameControllerButton)b);
        }

        NormalizedInput one;
        GamepadConvert::Convert(raw, deadzone_, one, sensitivity_, map_);
        if (log_sticks_) LogSticks(p, raw, one.lx, one.ly, one.rx, one.ry);
        one.buttons |= p.pending_buttons;
        p.pending_buttons = 0;

        out.buttons |= one.buttons;
        out.lx = Pick(out.lx, one.lx);
        out.ly = Pick(out.ly, one.ly);
        out.rx = Pick(out.rx, one.rx);
        out.ry = Pick(out.ry, one.ry);
    }
}

// The stick log: a line when a raw stick moved by more than ~3 % of its range since the last line (at most 8 lines a
// second), and every 5 s the window's range of the raw left stick (the resting noise when the stick is untouched). Raw =
// SDL_GameControllerGetAxis (-32768..32767, y down positive); norm = after the deadzone and the sensitivity; wire = the
// DualShock 2 byte (0x00 left / up, 0x80 centre, 0xFF right / down).
void GamepadBackend::LogSticks(Pad& pad, const GamepadRaw& raw, float lx, float ly, float rx, float ry) {
    const uint64_t now = SteadyNs();
    const int16_t lxr = raw.axis[SDL_CONTROLLER_AXIS_LEFTX], lyr = raw.axis[SDL_CONTROLLER_AXIS_LEFTY];
    const int16_t rxr = raw.axis[SDL_CONTROLLER_AXIS_RIGHTX], ryr = raw.axis[SDL_CONTROLLER_AXIS_RIGHTY];
    if (pad.window_start_ns == 0) pad.window_start_ns = now;
    pad.left_min[0] = std::min(pad.left_min[0], lxr);
    pad.left_max[0] = std::max(pad.left_max[0], lxr);
    pad.left_min[1] = std::min(pad.left_min[1], lyr);
    pad.left_max[1] = std::max(pad.left_max[1], lyr);
    const int16_t cur[4] = {lxr, lyr, rxr, ryr};
    bool moved = pad.last_line_ns == 0;
    for (int i = 0; i < 4; ++i) moved = moved || std::abs(int(cur[i]) - int(pad.logged[i])) > 1000;
    if (now - pad.last_line_ns >= 1000000000ull) pad.lines_in_second = 0;
    if (moved && pad.lines_in_second < 8) {
        std::fprintf(stderr, "input: sticks pad %d: LEFTX %6d LEFTY %6d  RIGHTX %6d RIGHTY %6d | norm L (%+.2f, %+.2f) R (%+.2f, %+.2f) | wire "
                             "L (0x%02X, 0x%02X) R (0x%02X, 0x%02X) | LT %d RT %d\n",
                     pad.instance_id, lxr, lyr, rxr, ryr, lx, ly, rx, ry, WireByte(lx), WireByte(ly), WireByte(rx), WireByte(ry),
                     raw.axis[SDL_CONTROLLER_AXIS_TRIGGERLEFT], raw.axis[SDL_CONTROLLER_AXIS_TRIGGERRIGHT]);
        for (int i = 0; i < 4; ++i) pad.logged[i] = cur[i];
        if (pad.lines_in_second == 0) pad.last_line_ns = now;
        ++pad.lines_in_second;
    }
    if (now - pad.window_start_ns >= 5000000000ull) {
        std::fprintf(stderr, "input: left stick pad %d, 5 s range: LEFTX %d..%d LEFTY %d..%d (deadzone %.0f raw units)\n", pad.instance_id,
                     pad.left_min[0], pad.left_max[0], pad.left_min[1], pad.left_max[1], deadzone_ * 32767.f);
        pad.window_start_ns = now;
        pad.left_min[0] = pad.left_min[1] = 32767;
        pad.left_max[0] = pad.left_max[1] = -32768;
    }
}

bool GamepadBackend::IsActive() const {
    return enabled_ && !pads_.empty() && selection_ != kSelectionNone;
}

}  // namespace ps2r::platform::sdl
