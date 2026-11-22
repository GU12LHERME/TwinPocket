// platform/nativeee (host side) -- te_port_settings.h: the Android port's own settings page. See the header.
#include "te_port_settings.h"

#include <cstdio>
#include <fstream>
#include <iterator>

#include "engine/core/config/settings_file.h"
#include "te_services.h"
#include "te_display.h"
#include "te_host.h"
#include "te_input.h"
#include "te_lod_policy.h"

namespace te::port_settings {

namespace {

// The choices, as ps2r.ini words and as the menu shows them (lower case: the game's menu font)
const char* const kDriverKeys[] = {"openslES", "aaudio", "android", "auto"};
const char* const kDriverNames[] = {"opensl es", "aaudio", "audiotrack", "auto"};
constexpr uint32_t kDrivers = 4, kDefaultDriver = 0;   // OpenSL ES: the driver that played cleanly on the Retroid Pocket 6
const char* const kBufferKeys[] = {"1024", "2048", "4096"};
constexpr uint32_t kBuffers = 3, kDefaultBuffer = 1;
const char* const kDeadzoneKeys[] = {"0.05", "0.10", "0.15", "0.20", "0.25"};
const float kDeadzones[] = {0.05f, 0.10f, 0.15f, 0.20f, 0.25f};
constexpr uint32_t kDeadzoneCount = 5, kDefaultDeadzone = 2;    // 0.15: the engine's own (InputSettings)
const char* const kSensitivityKeys[] = {"1.00", "1.33", "1.66", "2.00"};
const float kSensitivities[] = {1.00f, 1.33f, 1.66f, 2.00f};
constexpr uint32_t kSensitivityCount = 4, kDefaultSensitivity = 1;   // 1.33: the engine's own
// the draw distance's levels (te_lod_policy.h): ps2r.ini words, and their percents of the game's distances
const char* const kDistanceKeys[] = {"original", "medium", "long", "max", "extreme", "ultra"};
const uint32_t kDistancePercents[] = {100, 200, 300, 400, 600, 800};
constexpr uint32_t kDistanceCount = 6, kDefaultDistance = 0;   // the game's own
constexpr uint32_t kDistanceShown = 4;   // the menus offer original .. max (4x); extreme / ultra stay readable, shown as max

struct State {
    uint32_t driver = kDefaultDriver, buffer = kDefaultBuffer;
    uint32_t running_driver = kDefaultDriver, running_buffer = kDefaultBuffer;   // what this run started with
    uint32_t deadzone = kDefaultDeadzone, sensitivity = kDefaultSensitivity;
    uint32_t distance = kDefaultDistance;
    // the action rows' confirmation: the row pressed once (RowCount = none), the boundary it was pressed at, and the last one
    // done (its label says so until the page is left)
    uint32_t armed = RowCount;
    uint64_t armed_at = 0;
    uint32_t done = RowCount;
} g;

constexpr uint64_t kConfirmBoundaries = 300;   // a second press within ~5 s confirms

uint64_t Now() { return Services::Get().clock().BoundaryCount(); }
bool Armed(uint32_t row) { return g.armed == row && Now() - g.armed_at <= kConfirmBoundaries; }

uint32_t IndexOf(const char* const* keys, uint32_t count, const std::string& value, uint32_t fallback) {
    for (uint32_t i = 0; i < count; ++i)
        if (value == keys[i]) return i;
    return fallback;
}

uint32_t Read(const std::string& text, const char* key, const char* const* keys, uint32_t count, uint32_t fallback) {
    std::string v;
    if (!ps2r::SettingsFile::GetFromText(text, key, &v)) return fallback;
    const uint32_t i = IndexOf(keys, count, v, count);
    if (i == count) {
        std::fprintf(stderr, "[twinsanityevolved] android settings: %s = %s is not one of the page's values: the default\n", key, v.c_str());
        return fallback;
    }
    return i;
}

// A port key into ps2r.ini (the engine's writer keeps every other line; its Set refuses keys outside the engine's schema, so the
// text is edited with SetInText and written whole through a temporary, as SettingsFile writes it)
void Persist(const char* key, const std::string& value) {
    const std::string& path = GetSettings().settings_path;
    if (path.empty()) return;
    std::string text;
    {
        std::ifstream in(path, std::ios::binary);
        if (in) text.assign(std::istreambuf_iterator<char>(in), {});
    }
    bool changed = false;
    const std::string next = ps2r::SettingsFile::SetInText(text, key, value, &changed);
    if (!changed) return;
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        out << next;
        if (!out) {
            std::fprintf(stderr, "[twinsanityevolved] android settings: %s = %s not written (%s)\n", key, value.c_str(), tmp.c_str());
            return;
        }
    }
    if (std::rename(tmp.c_str(), path.c_str()) != 0)
        std::fprintf(stderr, "[twinsanityevolved] android settings: %s = %s not written (rename to %s)\n", key, value.c_str(), path.c_str());
}

void ApplySticks() { te::input::SetStickResponse(kDeadzones[g.deadzone], kSensitivities[g.sensitivity]); }

}  // namespace

void Configure(const std::string& ini_text) {
#if defined(__ANDROID__)
    g.driver = g.running_driver = Read(ini_text, "android.audio_driver", kDriverKeys, kDrivers, kDefaultDriver);
    g.buffer = g.running_buffer = Read(ini_text, "android.audio_buffer", kBufferKeys, kBuffers, kDefaultBuffer);
    g.deadzone = Read(ini_text, "android.stick_deadzone", kDeadzoneKeys, kDeadzoneCount, kDefaultDeadzone);
    g.sensitivity = Read(ini_text, "android.stick_sensitivity", kSensitivityKeys, kSensitivityCount, kDefaultSensitivity);
    {
        // 0.5.0 - 0.6.0 wrote percents (100 / 150 / 200 / 300): read as the nearest level (the launcher rewrites the key once)
        std::string v;
        if (ps2r::SettingsFile::GetFromText(ini_text, "android.draw_distance", &v) && (v == "100" || v == "150" || v == "200" || v == "300"))
            g.distance = v == "100" ? 0u : v == "300" ? 2u : 1u;
        else g.distance = Read(ini_text, "android.draw_distance", kDistanceKeys, kDistanceCount, kDefaultDistance);
        if (g.distance >= kDistanceShown) g.distance = kDistanceShown - 1;   // 0.8.2's extreme (6x) / ultra (8x): max (4x)
        te::lod::SetPercent(kDistancePercents[g.distance]);
    }
    ApplySticks();
    std::fprintf(stderr, "[twinsanityevolved] %s\n", Describe().c_str());
#else
    (void)ini_text;
#endif
}

uint32_t Count() {
#if defined(__ANDROID__)
    return RowCount;
#else
    return 0;
#endif
}

std::string Title() { return Count() ? "android settings" : ""; }

uint32_t Choices(uint32_t row) {
    switch (row) {
    case RowAudioDriver: return kDrivers;
    case RowAudioBuffer: return kBuffers;
    case RowStickDeadzone: return kDeadzoneCount;
    case RowStickSensitivity: return kSensitivityCount;
    case RowRestoreDefaults: return 1;   // an action (Kind 1): one entry
    case RowReturnToLauncher: return 1;
    default: return 0;
    }
}

uint32_t Kind(uint32_t row) { return row == RowRestoreDefaults || row == RowReturnToLauncher ? 1u : 0u; }

std::string RowLabel(uint32_t row) {
    switch (row) {
    case RowAudioDriver: return "audio driver";
    case RowAudioBuffer: return "audio buffer";
    case RowStickDeadzone: return "stick deadzone";
    case RowStickSensitivity: return "stick sensitivity";
    case RowRestoreDefaults: return Armed(row) ? "restore defaults? press again" : g.done == row ? "defaults restored" : "restore defaults";
    case RowReturnToLauncher: return Armed(row) ? "return to launcher? press again" : g.done == row ? "returning to launcher" : "return to launcher";
    default: return "";
    }
}

std::string ChoiceLabel(uint32_t row, uint32_t choice) {
    // the audio rows apply at the next start: the chosen value says so while it differs from this run's
    const auto next = [](bool pending, std::string s) { return pending ? s + " (next start)" : s; };
    switch (row) {
    case RowAudioDriver:
        return choice < kDrivers ? next(choice == g.driver && g.driver != g.running_driver, kDriverNames[choice]) : "";
    case RowAudioBuffer:
        return choice < kBuffers ? next(choice == g.buffer && g.buffer != g.running_buffer, kBufferKeys[choice]) : "";
    case RowStickDeadzone: return choice < kDeadzoneCount ? kDeadzoneKeys[choice] : "";
    case RowStickSensitivity: return choice < kSensitivityCount ? std::string("x") + kSensitivityKeys[choice] : "";
    default: return "";
    }
}

uint32_t Value(uint32_t row) {
    switch (row) {
    case RowAudioDriver: return g.driver;
    case RowAudioBuffer: return g.buffer;
    case RowStickDeadzone: return g.deadzone;
    case RowStickSensitivity: return g.sensitivity;
    default: return 0;   // restore defaults: always "no" (the row snaps back once it has acted)
    }
}

void Set(uint32_t row, uint32_t choice) {
    if (choice >= Choices(row)) return;
    switch (row) {
    case RowAudioDriver:
        g.driver = choice;
        Persist("android.audio_driver", kDriverKeys[choice]);
        break;
    case RowAudioBuffer:
        g.buffer = choice;
        Persist("android.audio_buffer", kBufferKeys[choice]);
        break;
    case RowStickDeadzone:
        g.deadzone = choice;
        Persist("android.stick_deadzone", kDeadzoneKeys[choice]);
        ApplySticks();
        break;
    case RowStickSensitivity:
        g.sensitivity = choice;
        Persist("android.stick_sensitivity", kSensitivityKeys[choice]);
        ApplySticks();
        break;
    case RowRestoreDefaults:
        // the first press asks (the label: "press again"), a second within ~5 s restores
        if (!Armed(row)) {
            g.armed = row;
            g.armed_at = Now();
            g.done = RowCount;
            return;
        }
        g.armed = RowCount;
        g.done = row;
        // this page's rows and the graphic options of the Android profile (te_display.h): 16:9, full screen (immersive),
        // vsync on, the bilinear filter, the resolution 1x, the frame rate 60 (the presenter shows every picture the game
        // makes: the game's own pace), the fps counter off, the draw distance original. The game's own widescreen option is
        // the game's, kept in its save: not changed here
        g.driver = kDefaultDriver;
        g.buffer = kDefaultBuffer;
        g.deadzone = kDefaultDeadzone;
        g.sensitivity = kDefaultSensitivity;
        Persist("android.audio_driver", kDriverKeys[g.driver]);
        Persist("android.audio_buffer", kBufferKeys[g.buffer]);
        Persist("android.stick_deadzone", kDeadzoneKeys[g.deadzone]);
        Persist("android.stick_sensitivity", kSensitivityKeys[g.sensitivity]);
        ApplySticks();
        SetDrawDistance(kDefaultDistance);
        te::display::Set(te::display::RowFrameRate, 1);   // 60 first: vsync is refused while uncapped
        te::display::Set(te::display::RowVSync, te::display::kDefaultVSync ? 1u : 0u);
#if defined(__ANDROID__)
        te::display::Set(te::display::RowAspect, 1);   // the Android row's 16:9 (original 4:3 is 0)
#else
        te::display::Set(te::display::RowAspect, uint32_t(te::display::kDefaultAspect));
#endif
        te::display::Set(te::display::RowTextureFilter, uint32_t(te::display::kDefaultTextureFilter));
        te::display::Set(te::display::RowScale, 0);
        te::display::Set(te::display::RowFpsCounter, 0);
        te::display::Set(te::display::RowAnisotropy, 0);   // off (refused, and nothing to do, without the extension)
        std::fprintf(stderr, "[twinsanityevolved] android settings: the defaults restored\n");
        break;
    case RowReturnToLauncher:
        if (!Armed(row)) {
            g.armed = row;
            g.armed_at = Now();
            g.done = RowCount;
            return;
        }
        g.armed = RowCount;
        g.done = row;
        // the desktop window's close path: the next window pump sees the quit and the runner leaves through te::RequestExit(0)
        std::fprintf(stderr, "[twinsanityevolved] android settings: return to launcher (the session ends as a window close: %s)\n",
                     te::input::RequestWindowClose() ? "quit posted" : "SDL refused the quit event");
        return;
    default: return;
    }
    std::fprintf(stderr, "[twinsanityevolved] %s\n", Describe().c_str());
}

std::string Describe() {
    char buf[320];
    std::snprintf(buf, sizeof buf, "android settings: audio driver %s%s, audio buffer %s%s, stick deadzone %s, stick sensitivity %s, "
                  "draw distance %s", kDriverKeys[g.driver], g.driver != g.running_driver ? " (next start)" : "", kBufferKeys[g.buffer],
                  g.buffer != g.running_buffer ? " (next start)" : "", kDeadzoneKeys[g.deadzone], kSensitivityKeys[g.sensitivity],
                  kDistanceKeys[g.distance]);
    return buf;
}

uint32_t DrawDistanceChoices() { return Count() ? kDistanceShown : 0; }
uint32_t DrawDistanceChoice() { return g.distance; }

std::string DrawDistanceLabel(uint32_t choice) {
    static const char* const kNames[] = {"original", "medium (2x)", "long (3x)", "max (4x)", "extreme (6x)", "ultra (8x)"};
    return choice < kDistanceCount ? kNames[choice] : "";
}

void SetDrawDistance(uint32_t choice) {
    if (choice >= kDistanceShown) return;
    g.distance = choice;
    Persist("android.draw_distance", kDistanceKeys[choice]);
    te::lod::SetPercent(kDistancePercents[choice]);
    std::fprintf(stderr, "[twinsanityevolved] android settings: draw distance %s (the levels of detail at %u %% of the game's "
                 "distances; experimental)\n", kDistanceKeys[choice], kDistancePercents[choice]);
}

}  // namespace te::port_settings
