// tools/android/test_settings_defaults.cpp -- the texture filter a run starts with, read the way runner/main.cpp reads
// ps2r.ini, and the in-game row's write read back (display.cpp Set -> SettingsFile::Set -> the next start's parse).
//
// Built twice by the Android workflow: with -DTE_DEFAULTS_ANDROID (the Android lane's defaults: bilinear when the file names
// no filter) and without (every other lane: the title's own choice, unchanged). Either way the player's choice in the file
// wins, every value of the row survives a write and a read, and the file's other keys are kept.
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <strings.h>

#include "engine/core/config/config.h"
#include "engine/core/config/config_loader.h"
#include "engine/core/config/settings_file.h"
#include "platform/nativeee/host/te_display.h"

namespace ps2r::host {
int StrCaseCmp(const char* a, const char* b) { return strcasecmp(a, b); }   // host_posix.cpp's, without the fibers
}

namespace {

int g_failures = 0;

void Check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

std::string ReadAll(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), {});
}

// runner/main.cpp: the platform defaults first, then the file's keys -- the display settings a run starts with
te::display::Settings Start(const std::string& text) {
    te::display::Settings d;   // no file: the display settings' defaults
    if (text.empty()) return d;
    ps2r::Config cfg;
    cfg.video.vsync = te::display::kDefaultVSync;
    cfg.video.aspect = static_cast<ps2r::AspectSel>(te::display::kDefaultAspect);
    cfg.video.fullscreen = static_cast<ps2r::FullscreenMode>(te::display::kDefaultFullscreen);
    cfg.video.texture_filter = static_cast<ps2r::TextureFilterSel>(te::display::kDefaultTextureFilter);
    std::string err;
    if (!ps2r::ConfigLoader::ApplyIniText(text, cfg, &err)) {
        std::printf("      parse error: %s\n", err.c_str());
        d.texture_filter = -1;
        return d;
    }
    d.aspect = int(cfg.video.aspect);
    d.texture_filter = int(cfg.video.texture_filter);
    d.vsync = cfg.video.vsync;
    d.fullscreen = int(cfg.video.fullscreen);
    return d;
}
int StartFilter(const std::string& text) { return Start(text).texture_filter; }

}  // namespace

int main(int argc, char** argv) {
    const std::string path = argc > 1 ? argv[1] : "/tmp/te_settings_defaults_ps2r.ini";
    std::remove(path.c_str());
#if defined(TE_DEFAULTS_ANDROID)
    const int expected_default = 2;
    std::printf("lane: Android (the default is bilinear)\n");
#else
    const int expected_default = 0;
    std::printf("lane: desktop / Vita (the default is the title's own)\n");
#endif
    Check(te::display::kDefaultTextureFilter == expected_default, "the lane's default texture filter");
    Check(StartFilter("") == expected_default, "no ps2r.ini: the lane's default");
    Check(StartFilter("game.text_language = english\n") == expected_default, "ps2r.ini without video.texture_filter: the lane's default");

    // the rest of the profile (te_display.h): Android 16:9, fullscreen (immersive), vsync on, the game's widescreen on;
    // elsewhere auto, windowed, vsync off, widescreen off
#if defined(TE_DEFAULTS_ANDROID)
    const int want_aspect = 2, want_fullscreen = 1;
    const bool want_vsync = true, want_widescreen = true;
#else
    const int want_aspect = 0, want_fullscreen = 0;
    const bool want_vsync = false, want_widescreen = false;
#endif
    for (const char* text : {"", "game.text_language = english\n"}) {
        const te::display::Settings d = Start(text);
        char what[128];
        std::snprintf(what, sizeof what, "%s: aspect %d, fullscreen %d, vsync %d (the lane's profile)", *text ? "ps2r.ini without the keys" : "no ps2r.ini",
                      d.aspect, d.fullscreen, int(d.vsync));
        Check(d.aspect == want_aspect && d.fullscreen == want_fullscreen && d.vsync == want_vsync, what);
    }
    Check(te::display::kDefaultWidescreen == want_widescreen, "the game's widescreen option for a game with no save: the lane's");
    // a player's choice in the file wins over the profile (an update never resets it)
    {
        const te::display::Settings d = Start("video.aspect = 4:3\nvideo.fullscreen = windowed\nvideo.vsync = off\nvideo.texture_filter = title\n");
        Check(d.aspect == 1 && d.fullscreen == 0 && !d.vsync && d.texture_filter == 0, "the player's saved aspect, window, vsync and filter kept");
        const te::display::Settings e = Start("video.aspect = 16:9\nvideo.fullscreen = fullscreen\nvideo.vsync = on\n");
        Check(e.aspect == 2 && e.fullscreen == 1 && e.vsync, "16:9, fullscreen, vsync on read back as written");
    }

    // the Android menus' rules (te_display.h ApplyLaneRules, display.cpp Configure): always full screen, never stretched,
    // 4:3 or 16:9, 30 or 60 -- older values read into range; nothing changes on the other lanes
    {
        struct Case { int aspect, fit, fullscreen, rate; int want_aspect, want_fit, want_fullscreen, want_rate; const char* what; };
#if defined(TE_DEFAULTS_ANDROID)
        const Case cases[] = {
            {0, 1, 1, 1, 1, 1, 1, 1, "auto -> original (4:3)"},
            {4, 1, 1, 1, 2, 1, 1, 1, "21:9 -> wide (16:9)"},
            {3, 1, 1, 1, 2, 1, 1, 1, "16:10 -> wide (16:9)"},
            {1, 0, 0, 3, 1, 1, 1, 1, "4:3 kept; stretch -> fit; windowed -> fullscreen; 144 -> 60"},
            {2, 1, 2, 0, 2, 1, 1, 0, "16:9 kept; borderless -> fullscreen; 30 kept"},
            {2, 1, 1, 5, 2, 1, 1, 1, "uncapped -> 60"},
        };
#else
        const Case cases[] = {
            {0, 1, 0, 3, 0, 1, 0, 3, "desktop: auto, windowed, 144 kept"},
            {4, 0, 2, 5, 4, 0, 2, 5, "desktop: 21:9, stretch, borderless, uncapped kept"},
        };
#endif
        for (const Case& c : cases) {
            te::display::Settings d;
            d.aspect = c.aspect; d.fit = c.fit; d.fullscreen = c.fullscreen; d.frame_rate = c.rate;
            te::display::ApplyLaneRules(d);
            Check(d.aspect == c.want_aspect && d.fit == c.want_fit && d.fullscreen == c.want_fullscreen && d.frame_rate == c.want_rate, c.what);
        }
    }

    // the row's three choices (display.cpp kFilterKeys) written as the menu writes them, read back as the next start reads them
    std::string err;
    Check(ps2r::SettingsFile::Set(path, "game.text_language", "english", &err), "a first key written");
    const char* const keys[] = {"title", "point", "bilinear"};
    for (int i = 0; i < 3; ++i) {
        const bool written = ps2r::SettingsFile::Set(path, "video.texture_filter", keys[i], &err);
        const int read = StartFilter(ReadAll(path));
        char what[96];
        std::snprintf(what, sizeof what, "the row's \"%s\" written and read back as %d", keys[i], read);
        Check(written && read == i, what);
    }
    std::string v;
    Check(ps2r::SettingsFile::GetFromText(ReadAll(path), "game.text_language", &v) && v == "english", "the file's other key kept");
    std::remove(path.c_str());
    std::printf("%s\n", g_failures ? "SETTINGS DEFAULTS: FAILED" : "SETTINGS DEFAULTS: OK");
    return g_failures ? 1 : 0;
}
