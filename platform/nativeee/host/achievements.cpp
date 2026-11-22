// platform/nativeee (host side) -- te_achievements.h: the Android port's local achievements. See the header.
#include "te_achievements.h"

#include <cctype>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <iterator>
#include <map>
#include <mutex>
#include <sstream>

#include "te_host.h"
#include "te_mods.h"
#include "te_texts.h"

namespace te::achievements {

namespace {

// id (stable: the ps2r.ini-folder file's key), the area that unlocks it (the level select's chunk paths), the line shown
struct Def { const char* id; const char* chunk; const char* line; };
const Def kDefs[] = {
    {"reach_island", "levels\\earth\\hub\\beach", "achievement: island explorer"},
    {"reach_iceberg", "levels\\ice\\hub\\labext", "achievement: cold welcome"},
    {"reach_academy", "levels\\school\\sch_hub\\sch_hub", "achievement: back to school"},
    {"reach_other_island", "levels\\altearth\\hub\\alta", "achievement: the other side"},
    {"reach_dimension", "levels\\altearth\\lab\\labext", "achievement: a new dimension"},
};

std::mutex g_lock;
bool g_loaded = false;
std::map<std::string, long long> g_unlocked;   // id -> Unix time
std::string g_toast;
uint64_t g_toast_until = 0, g_now = 0;
bool g_text_registered = false;

std::string Lower(std::string s) {
    for (char& c : s) c = char(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

// achievements.txt beside the settings file (the game folder); "" without a settings file (a deterministic run)
std::string FilePath() {
    const std::string& settings = GetSettings().settings_path;
    if (settings.empty()) return {};
    const size_t slash = settings.find_last_of("/\\");
    return (slash == std::string::npos ? std::string() : settings.substr(0, slash + 1)) + "achievements.txt";
}

void Load() {
    if (g_loaded) return;
    g_loaded = true;
    const std::string path = FilePath();
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        const size_t eq = line.find('=');
        if (eq == std::string::npos || line[0] == '#') continue;
        g_unlocked[line.substr(0, eq)] = std::atoll(line.c_str() + eq + 1);
    }
    if (!g_unlocked.empty()) std::fprintf(stderr, "[twinsanityevolved] achievements: %zu unlocked (%s)\n", g_unlocked.size(), path.c_str());
}

// written whole through a temporary renamed into place (a crash never leaves half a file)
void Save() {
    const std::string path = FilePath();
    if (path.empty()) return;
    std::ostringstream text;
    text << "# TwinsanityEvolved Android: local achievements (id = Unix time of the unlock); the launcher's Conquistas page resets them\n";
    for (const auto& kv : g_unlocked) text << kv.first << "=" << kv.second << "\n";
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        out << text.str();
        if (!out) return;
    }
    std::rename(tmp.c_str(), path.c_str());
}

void RegisterText() {
    if (g_text_registered) return;
    g_text_registered = true;
    te::texts::Register("achievements", [](std::vector<te::texts::Line>& out) {
        std::lock_guard<std::mutex> lock(g_lock);
        if (g_toast.empty() || g_now > g_toast_until) return;
        te::texts::Line l;
        l.x = 0.04f;
        l.y = 0.92f;
        l.scale = 0.5f;
        l.colour = 0x80C0C0C0u;   // the game's white
        l.alignment = te::texts::Top | te::texts::Left;
        l.text = g_toast;
        out.push_back(l);
    });
}

}  // namespace

void OnArea(const std::string& from, const std::string& to, bool playing, uint64_t boundary) {
#if defined(__ANDROID__)
    std::lock_guard<std::mutex> lock(g_lock);
    g_now = boundary;
    RegisterText();
    if (from.empty() || to.empty() || !playing || te::mods::AnyEnabled()) return;   // a load, the title, a warp: no visit
    const std::string area = Lower(to);
    for (const Def& d : kDefs) {
        if (area != d.chunk) continue;
        Load();
        if (g_unlocked.count(d.id)) return;
        g_unlocked[d.id] = static_cast<long long>(std::time(nullptr));
        Save();
        g_toast = d.line;
        g_toast_until = boundary + 240;   // about four seconds
        std::fprintf(stderr, "[twinsanityevolved] achievements: '%s' unlocked (entered %s from %s)\n", d.id, to.c_str(), from.c_str());
        return;
    }
#else
    (void)from; (void)to; (void)playing; (void)boundary;
#endif
}

}  // namespace te::achievements
