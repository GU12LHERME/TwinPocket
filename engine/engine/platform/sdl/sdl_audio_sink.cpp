// platform/sdl/sdl_audio_sink.cpp -- see sdl_audio_sink.h.
#include "engine/platform/sdl/sdl_audio_sink.h"

#include <SDL.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <cstdlib>

#include "engine/platform/sdl/audio_pull_ring.h"

namespace ps2r::platform::sdl {

namespace {
#if defined(__ANDROID__)
constexpr int kDeviceBufferSamples = 2048;  // per callback, ~42.7 ms at 48 kHz (see RequestedBufferFrames)
#else
constexpr int kDeviceBufferSamples = 512;   // per callback, ~10.7 ms at 48 kHz
#endif
uint64_t NowNs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
const char* FormatName(SDL_AudioFormat f) {
    switch (f) {
        case AUDIO_F32SYS: return "f32";
        case AUDIO_S16SYS: return "s16";
        case AUDIO_S32SYS: return "s32";
        case AUDIO_U8: return "u8";
        default: return "other";
    }
}
}  // namespace

int SdlAudioSink::RequestedBufferFrames() {
    if (const char* e = std::getenv("TE_AUDIO_BUFFER_FRAMES")) {
        const long n = std::strtol(e, nullptr, 10);
        if (n >= 256 && n <= 8192 && (n & (n - 1)) == 0) return static_cast<int>(n);
        std::fprintf(stderr, "audio_sink: TE_AUDIO_BUFFER_FRAMES=%s ignored (a power of two, 256..8192)\n", e);
    }
    return kDeviceBufferSamples;
}

SdlAudioSink::CallbackStats SdlAudioSink::Stats() const {
    CallbackStats s;
    s.callbacks = callbacks_.load(std::memory_order_relaxed);
    s.late = late_.load(std::memory_order_relaxed);
    s.max_gap_us = max_gap_us_.load(std::memory_order_relaxed);
    s.max_callback_us = max_callback_us_.load(std::memory_order_relaxed);
    return s;
}

void SdlAudioSink::ResetMaxima() {
    max_gap_us_.store(0, std::memory_order_relaxed);
    max_callback_us_.store(0, std::memory_order_relaxed);
}

SdlAudioSink::~SdlAudioSink() { Close(); }

void SdlAudioSink::Callback(void* userdata, uint8_t* stream, int len) {
    auto* self = static_cast<SdlAudioSink*>(userdata);
    float* out = reinterpret_cast<float*>(stream);
    const int frames = len / (AudioPullRing::kChannels * static_cast<int>(sizeof(float)));
    // timing only (no lock, no allocation, no log on this thread): the gap since the previous callback and this one's cost
    const uint64_t t0 = NowNs();
    if (self->last_callback_ns_) {
        const uint64_t gap_us = (t0 - self->last_callback_ns_) / 1000;
        const uint64_t buffer_us = self->got_freq_ > 0 ? uint64_t(frames) * 1000000u / uint64_t(self->got_freq_) : 0;
        if (buffer_us && gap_us > buffer_us * 3 / 2) self->late_.fetch_add(1, std::memory_order_relaxed);
        if (gap_us > self->max_gap_us_.load(std::memory_order_relaxed)) self->max_gap_us_.store(uint32_t(gap_us), std::memory_order_relaxed);
    }
    self->last_callback_ns_ = t0;
    if (self->refill_) self->refill_(self->refill_user_, frames);   // the owner's wall-clocked producer, if any
    const int samples = frames * AudioPullRing::kChannels;
    if (self->muted_.load(std::memory_order_acquire)) {
        self->ring_->Pull(out, frames);   // still drained: what the game makes meanwhile is not kept for later
        if (self->mute_state_ == 0) {     // the first muted buffer: the live audio faded out over it (no click)
            for (int i = 0; i < frames; ++i) {
                const float g = 1.0f - float(i + 1) / float(frames);
                for (int c = 0; c < AudioPullRing::kChannels; ++c) out[i * AudioPullRing::kChannels + c] *= g;
            }
        } else {
            std::memset(out, 0, static_cast<size_t>(samples) * sizeof(float));
        }
        self->mute_state_ = 1;
    } else if (self->mute_state_ != 0) {
        if (self->ring_->Available() < frames) {   // back, but no whole buffer of new audio yet: silence, not an old pad
            std::memset(out, 0, static_cast<size_t>(samples) * sizeof(float));
            self->mute_state_ = 2;
        } else {
            self->ring_->Pull(out, frames);
            const int ramp = std::min(frames, std::max(1, self->got_freq_ / 100));   // ~10 ms fade in
            for (int i = 0; i < ramp; ++i) {
                const float g = float(i + 1) / float(ramp);
                for (int c = 0; c < AudioPullRing::kChannels; ++c) out[i * AudioPullRing::kChannels + c] *= g;
            }
            self->mute_state_ = 0;
        }
    } else {
        self->ring_->Pull(out, frames);
    }
    if (self->tap_) self->tap_(self->tap_user_, out, frames);
    const uint64_t cb_us = (NowNs() - t0) / 1000;
    if (cb_us > self->max_callback_us_.load(std::memory_order_relaxed)) self->max_callback_us_.store(uint32_t(cb_us), std::memory_order_relaxed);
    self->callbacks_.fetch_add(1, std::memory_order_relaxed);
}

bool SdlAudioSink::Open(const AudioSpec& spec, AudioPullRing& ring) {
    if (device_ != 0) return true;
    if (spec.channels != AudioPullRing::kChannels) {
        std::fprintf(stderr, "audio_sink: %d channels requested, the ring is stereo\n",
                     spec.channels);
        return false;
    }

    if (!audio_subsystem_) {
        if (SDL_InitSubSystem(SDL_INIT_AUDIO) != 0) {
            std::fprintf(stderr, "audio_sink: SDL_InitSubSystem(AUDIO) failed: %s\n",
                         SDL_GetError());
            return false;
        }
        audio_subsystem_ = true;
    }

    ring_ = &ring;

    SDL_AudioSpec want{};
    want.freq     = spec.sample_rate;
    want.format   = AUDIO_F32SYS;
    want.channels = static_cast<Uint8>(spec.channels);
    want.samples  = static_cast<Uint16>(RequestedBufferFrames());
    want.callback = &SdlAudioSink::Callback;
    want.userdata = this;

    SDL_AudioSpec got{};
    // allowed_changes = 0: SDL converts internally if the hardware differs,
    // so the callback always sees exactly `want`.
    const SDL_AudioDeviceID dev = SDL_OpenAudioDevice(nullptr, 0, &want, &got, 0);
    if (dev == 0) {
        std::fprintf(stderr, "audio_sink: SDL_OpenAudioDevice failed: %s (continuing without sound)\n",
                     SDL_GetError());
        ring_ = nullptr;
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
        audio_subsystem_ = false;
        return false;
    }
    std::fprintf(stderr, "audio_sink: driver %s; requested %d Hz %s %d ch %d frames; obtained %d Hz %s %d ch %d frames "
                         "(%u bytes, %.1f ms a callback)%s\n",
                 SDL_GetCurrentAudioDriver() ? SDL_GetCurrentAudioDriver() : "?", want.freq, FormatName(want.format), want.channels,
                 want.samples, got.freq, FormatName(got.format), got.channels, got.samples, got.size,
                 got.freq > 0 ? 1000.0 * got.samples / got.freq : 0.0,
                 // allowed_changes 0: the callback always gets the requested spec; what the OS stream itself opened with
                 // (AAudio / OpenSL ES) is in SDL's own logcat lines ("AAudio Try to open ...")
                 " (the callback's spec; SDL converts to the OS stream's)");
    device_       = dev;
    got_freq_     = got.freq;
    got_channels_ = got.channels;
    got_samples_  = got.samples;
    paused_ = false;
    SDL_PauseAudioDevice(dev, 0);   // start the callback
    return true;
}

void SdlAudioSink::Close() {
    if (device_ != 0) {
        // Returns only after any in-flight callback has finished: after this
        // the device thread no longer touches the ring.
        SDL_CloseAudioDevice(device_);
        device_ = 0;
        ring_   = nullptr;
    }
    if (audio_subsystem_) {
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
        audio_subsystem_ = false;
    }
    paused_ = false;
}

void SdlAudioSink::SetPaused(bool paused) {
    if (!device_ || paused_ == paused) return;
    SDL_PauseAudioDevice(device_, paused ? 1 : 0);
    paused_ = paused;
}

bool SdlAudioSink::Running() const { return device_ != 0; }

int SdlAudioSink::DeviceSampleRate() const   { return got_freq_; }
int SdlAudioSink::DeviceChannels() const     { return got_channels_; }
int SdlAudioSink::DeviceBufferFrames() const { return got_samples_; }

}  // namespace ps2r::platform::sdl
