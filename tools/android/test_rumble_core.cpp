// The game's vibration player's state (platform/nativeee/host/te_rumble.h Core): the game's changes become bounded effects
// renewed while its motors run, no duplicate effect for the same motors, a stop only when something plays, and a suspend
// (focus lost, background, controller removed, shutdown) that stops at once and starts nothing again by itself.
#include "te_rumble.h"
#include <cstdio>

using te::rumble::Command;
using te::rumble::Core;
using te::rumble::Motors;

static int failures = 0;
static void Expect(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) failures++;
}
static Motors M(bool small, unsigned big) { Motors m; m.small = small; m.big = uint8_t(big); return m; }

int main() {
    Core c;
    Command k = c.Set(M(false, 0), 0);
    Expect(k.kind == Command::None, "stopped -> stop: nothing sent");
    k = c.Set(M(false, 200), 10);
    Expect(k.kind == Command::Play && k.low == 200 * 257 && k.high == 0 && k.length_ms == Core::kLengthMs, "big 200: low 51400, high 0, bounded");
    Expect(c.Set(M(false, 200), 20).kind == Command::None, "the same motors again: no duplicate effect");
    Expect(c.Tick(10 + Core::kRefreshMs - 1).kind == Command::None, "renewal not before kRefreshMs");
    k = c.Tick(10 + Core::kRefreshMs);
    Expect(k.kind == Command::Play && k.low == 200 * 257, "renewed at kRefreshMs with the same motors");
    Expect(Core::kRefreshMs < Core::kLengthMs, "renewal comes before the effect ends (no gap)");
    k = c.Set(M(true, 200), 300);
    Expect(k.kind == Command::Play && k.high == 65535 && k.low == 200 * 257, "small on: high 65535 (both motors)");
    k = c.Set(M(false, 0), 400);
    Expect(k.kind == Command::Stop && !c.Playing(), "the game's stop: stopped");
    Expect(c.Tick(10000).kind == Command::None, "stopped: never renewed");
    // suspend while playing
    c.Set(M(false, 255), 500);
    k = c.Suspend();
    Expect(k.kind == Command::Stop && !c.Playing(), "focus lost while running: stopped at once");
    Expect(c.Set(M(true, 255), 510).kind == Command::None, "suspended: the game's change is not played");
    Expect(c.Tick(2000).kind == Command::None, "suspended: nothing renewed");
    c.Resume();
    Expect(c.Tick(3000).kind == Command::None && !c.Playing(), "focus back: nothing restarts by itself");
    k = c.Set(M(true, 0), 3100);
    Expect(k.kind == Command::Play && k.low == 0 && k.high == 65535, "after resume the game's next change plays (small only)");
    Expect(c.Suspend().kind == Command::Stop && c.Suspend().kind == Command::None, "a second suspend sends nothing more");
    std::printf("test_rumble_core: %s\n", failures ? "FAILED" : "ok");
    return failures ? 1 : 0;
}
