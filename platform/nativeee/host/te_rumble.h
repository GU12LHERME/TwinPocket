// te_rumble.h -- the game's own vibration (the DualShock 2's motors) played on the player's controller (Android).
//
// THE PATH: the game times each vibration itself (game/src/game/pads.cpp GamePadController::Update: a request's motors start,
// run while its time lasts, stop) and tells the pad only the changes -- SetPadMotors(small on/off, big 0..255), only while
// its own Vibration option is on (flags.vibration; DisableVibration and Pause send the motors' stop). Through libpad's
// SetActuatorDirect the values reach the engine's title::Input (SetMotors: the actuators' alignment, paused = stopped) and
// its MotorOutput -- which nothing installed until now: every command was accepted and nothing shook (host/pads.cpp).
//
// THE PLAYER (rumble.cpp): this Core turns the game's changes into effects of a bounded length that are renewed while the
// motors run (kLengthMs, renewed every kRefreshMs): a motor never outlives the game thread -- a stall, a crash, the app
// paused by Android -- by more than kLengthMs. Suspend (focus lost, the app to the background, the controller gone, the
// session ending) stops it at once, and nothing starts again until the game sends its next change.
//
// The motors: big (the DualShock's large, low-frequency motor) -> low frequency 0..65535; small (on/off, high frequency)
// -> high frequency 0 or 65535. A device with one motor gets what its API makes of the two (rumble.cpp).
#pragma once
#include <cstdint>

namespace te::rumble {

struct Motors {
    bool small = false;
    uint8_t big = 0;
    bool Any() const { return small || big != 0; }
    bool operator==(const Motors& o) const { return small == o.small && big == o.big; }
};

struct Command {
    enum Kind : uint8_t { None, Play, Stop } kind = None;
    uint16_t low = 0, high = 0;   // the big motor, the small one (0..65535)
    uint32_t length_ms = 0;
};

class Core {
public:
    static constexpr uint32_t kLengthMs = 400;    // an effect's length: the most a motor runs past the last renewal
    static constexpr uint32_t kRefreshMs = 200;   // renewed this often while the game's motors run

    // The game's change of the motors (the same motors again: nothing -- no duplicate effect)
    Command Set(Motors m, uint64_t now_ms) {
        if (suspended_) {
            // focus lost / background: nothing plays; the game's stop is remembered as nothing to play
            want_ = m;
            return {};
        }
        if (m == want_ && (playing_ || !m.Any())) return {};
        want_ = m;
        if (!m.Any()) return Stop();
        return Start(now_ms);
    }

    // A frame boundary: the effect renewed while the motors run
    Command Tick(uint64_t now_ms) {
        if (!playing_ || now_ms - started_ < kRefreshMs) return {};
        return Start(now_ms);
    }

    // Focus lost, the app to the background, the controller removed, the session ending: stopped at once
    Command Suspend() {
        suspended_ = true;
        want_ = {};
        return Stop();
    }

    // Focus back: nothing restarts by itself -- the game's next change plays
    void Resume() {
        suspended_ = false;
        want_ = {};
    }

    bool Playing() const { return playing_; }
    bool Suspended() const { return suspended_; }
    Motors Wanted() const { return want_; }

private:
    Command Start(uint64_t now_ms) {
        playing_ = true;
        started_ = now_ms;
        Command c;
        c.kind = Command::Play;
        c.low = uint16_t(want_.big * 257u);
        c.high = want_.small ? 65535 : 0;
        c.length_ms = kLengthMs;
        return c;
    }

    Command Stop() {
        if (!playing_) return {};
        playing_ = false;
        Command c;
        c.kind = Command::Stop;
        return c;
    }

    Motors want_{};
    bool playing_ = false;
    bool suspended_ = false;
    uint64_t started_ = 0;
};

}  // namespace te::rumble

namespace te { class Pads; }

// rumble.cpp (Android with SDL input; nothing elsewhere): the motor output installed on the game's pads, the SDL events
// that stop it (focus, background, the controller's removal), and the shutdown's stop.
namespace te::rumble {
// Installs the game's motor output (te_input.cpp Install, after the SDL input system is up). ps2r.ini android.rumble:
// auto (the default: the controller's motors, else the device's own vibrator) | controller (never the device's) | off.
void Install(te::Pads& pads, const char* mode);
// An SDL event seen by the input pump (a union SDL_Event, passed as its address: no SDL here)
void OnEvent(const void* sdl_event);
// Every boundary on the game thread: the running effect renewed
void Tick();
// The session ending: the motors stopped, the device closed
void Shutdown();
}  // namespace te::rumble
