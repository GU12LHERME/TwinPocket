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

// runner/main.cpp: the platform default first, then the file's keys
int StartFilter(const std::string& text) {
    if (text.empty()) return te::display::Settings{}.texture_filter;   // no file: the display settings' defaults
    ps2r::Config cfg;
    cfg.video.texture_filter = static_cast<ps2r::TextureFilterSel>(te::display::kDefaultTextureFilter);
    std::string err;
    if (!ps2r::ConfigLoader::ApplyIniText(text, cfg, &err)) {
        std::printf("      parse error: %s\n", err.c_str());
        return -1;
    }
    return int(cfg.video.texture_filter);
}

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
