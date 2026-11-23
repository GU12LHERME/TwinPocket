// tools/android/test_audio_ring_lead.cpp -- the audio ring with the Android device buffers, run in CI.
//
// The real-time producer (platform/nativeee/host/audio_service.cpp Refill) tops the ring up to
// min(pull + lead, trim - tick) before each device pull. With the ring's fixed 50 ms lead (2400 frames, trim 4000), a
// device pulling 4096 frames a callback can never be covered: part of every pull is padded (a repeated tail -- audible
// as distortion). AudioPullRing::SetDeviceFrames sizes the lead to the pull. This test runs that producer / consumer
// loop for 1024 / 2048 / 4096-frame pulls, with and without SetDeviceFrames, and counts the padded frames.
#include <algorithm>
#include <cstdio>
#include <vector>

#include "engine/platform/sdl/audio_pull_ring.h"

using Ring = ps2r::platform::sdl::AudioPullRing;

static unsigned long long Run(int pull, bool sized) {
    static Ring ring;   // large: not on the stack
    ring.~Ring();
    new (&ring) Ring();
    ring.SetPadWithTail(true);
    if (sized) ring.SetDeviceFrames(pull);
    ring.Prime();
    const int tick = Ring::kQuantumFrames;
    std::vector<float> mix(static_cast<size_t>(tick) * 2, 0.25f), out(static_cast<size_t>(pull) * 2);
    for (int i = 0; i < 2000; ++i) {
        const int target = std::min(pull + ring.LeadFrames(), ring.TrimFrames() - tick);
        while (ring.Available() < target) ring.Push(mix.data(), tick);
        ring.Pull(out.data(), pull);
    }
    return static_cast<unsigned long long>(ring.padded());
}

// A stopped stream (the app losing the screen stalls the game thread): with SetPadFade the pad fades out after one
// quantum and is silence after three; without it the tail repeats (the default, other platforms unchanged).
static float PadLevel(bool fade, int quantum_index) {
    static Ring ring;
    ring.~Ring();
    new (&ring) Ring();
    ring.SetPadWithTail(true);
    ring.SetPadFade(fade);
    const int q = Ring::kQuantumFrames;
    std::vector<float> tone(static_cast<size_t>(q) * 2), out(static_cast<size_t>(q) * 2);
    for (int i = 0; i < q; ++i) tone[size_t(i) * 2] = tone[size_t(i) * 2 + 1] = (i % 40 < 20) ? 0.5f : -0.5f;
    ring.Push(tone.data(), q);
    ring.Pull(out.data(), q);                       // the real quantum
    float peak = 0;
    for (int k = 0; k <= quantum_index; ++k) {     // then the stream is gone: pads only
        ring.Pull(out.data(), q);
        peak = 0;
        for (float v : out) peak = std::max(peak, v < 0 ? -v : v);
    }
    return peak;
}

int main() {
    int fail = 0;
    {
        const float off0 = PadLevel(false, 0), off5 = PadLevel(false, 5), on0 = PadLevel(true, 0), on3 = PadLevel(true, 3);
        std::printf("pad fade: off -- first pad %.2f, sixth pad %.2f; on -- first pad %.2f, fourth pad %.2f\n", off0, off5, on0, on3);
        if (off5 < 0.49f || on0 < 0.49f || on3 > 0.0001f) fail = 1;
    }
    for (int pull : {1024, 2048, 4096}) {
        const unsigned long long fixed = Run(pull, false), sized = Run(pull, true);
        std::printf("pull %4d frames: padded frames with the fixed 50 ms lead %llu, with SetDeviceFrames %llu\n", pull, fixed, sized);
        if (sized != 0) fail = 1;
    }
    std::printf("test_audio_ring_lead: %s\n", fail ? "FAIL" : "PASS");
    return fail;
}
