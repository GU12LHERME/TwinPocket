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

int main() {
    int fail = 0;
    for (int pull : {1024, 2048, 4096}) {
        const unsigned long long fixed = Run(pull, false), sized = Run(pull, true);
        std::printf("pull %4d frames: padded frames with the fixed 50 ms lead %llu, with SetDeviceFrames %llu\n", pull, fixed, sized);
        if (sized != 0) fail = 1;
    }
    std::printf("test_audio_ring_lead: %s\n", fail ? "FAIL" : "PASS");
    return fail;
}
