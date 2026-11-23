// te_display.h -- the port's DISPLAY settings -- the engine's Display page (aspect, resolution,
// texture filter, vsync, window mode) and the title's own MoveDisplay (the console's "centre screen" option) -- over the
// engine's ps2e_draw presentation, persisted in ps2r.ini (the engine's video.* keys), shown as rows of the title's GRAPHIC
// OPTIONS page through the seam (Platform::Graphics::DisplaySetting*).
#pragma once
#include <cstdint>
#include <string>

namespace te::display {

// The texture filter a run starts with when ps2r.ini does not name one (video.texture_filter): the title's own sampler
// choice on every lane but Android, where it is BILINEAR -- on the Retroid Pocket 6 (Adreno 740) the title's choice draws
// the additive glows (Aku Aku's mask, the glowing mushrooms, the waterfall's mist) as square blocks, and bilinear draws them
// as on the console (the player's test, APK 0.1.7). The row keeps all its choices; the file's value wins once written.
//
// THE ANDROID PROFILE (the Retroid Pocket 6): a new installation starts with the picture's shape 16:9 (the handheld's screen;
// with the game's own widescreen option on -- Platform::Graphics::WidescreenDefault -- the true 16:9 projection), the window
// full screen (SDL's fullscreen on Android is the system's immersive mode: no status or navigation bar), vsync on and the
// bilinear filter. Each applies only while ps2r.ini does not name the key: a value the player chose (in the game's menu or
// the launcher) is kept across updates. Every other lane keeps its defaults (the title's filter, auto, windowed, vsync off).
#if defined(__ANDROID__) || defined(TE_DEFAULTS_ANDROID)   // (TE_DEFAULTS_ANDROID: the host test of the Android defaults)
constexpr int kDefaultTextureFilter = 2;   // bilinear
constexpr int kDefaultAspect = 2;          // 16:9
constexpr int kDefaultFullscreen = 1;      // fullscreen (Android: immersive)
constexpr bool kDefaultVSync = true;
constexpr bool kDefaultWidescreen = true;  // the game's own widescreen option, for a game with no save yet
#else
constexpr int kDefaultTextureFilter = 0;   // title
constexpr int kDefaultAspect = 0;          // auto
constexpr int kDefaultFullscreen = 0;      // windowed
constexpr bool kDefaultVSync = false;      // the port's pacer times the presents
constexpr bool kDefaultWidescreen = false; // as on the console
#endif

// What the runner read from ps2r.ini (the engine's VideoConfig, the keys the port honours) before the device exists.
struct Settings {
    int aspect = kDefaultAspect;   // video.aspect: 0 auto (the TV's 4:3 in a window; the frame's own pixels headless -- the frozen
                             // judges' captures), 1 4:3, 2 16:9, 3 16:10, 4 21:9, 5 32:9
    int fit = 1;             // video.fit_mode: 0 stretch, 1 fit (letterbox), 2 fill, 3 integer, 4 custom (= fit here)
    int scale = 1;           // video.scale: the frame target's internal scale 1..4 (0 = 1; the PS Vita 1..2: display.cpp)
    int anisotropy = 1;      // video.anisotropy: 1 off (the default), 2 / 4 / 8 / 16 -- the Android row's (te_display.h RowAnisotropy)
    int texture_filter = kDefaultTextureFilter;  // video.texture_filter: 0 title, 1 point, 2 bilinear, 3 trilinear (bilinear here: ps2e_draw has no mip chain)
    bool vsync = kDefaultVSync;    // video.vsync (the port's default: off on the desktop -- its pacer times the presents; a windowed run may turn it on)
    int fullscreen = kDefaultFullscreen;   // video.fullscreen: 0 windowed, 1 fullscreen, 2 borderless
    int offset_x = 0;        // video.custom_offset_x/y: the title's MoveDisplay, in FRAME pixels (the PS2's +-64 across, +-32 down)
    int offset_y = 0;
    bool fps_counter = false;   // display.fps_counter: render fps + game Hz top-right through the GAME's text system
    int frame_rate = 1;         // video.frame_rate: 0 = 30, 1 = 60 (default), 2 = 120, 3 = 144, 4 = 240, 5 = uncapped -- the
                                // presenter's cadence (the engine's PresentPacer rates); `60` shows every picture the game makes,
                                // so at a game rate of 50 Hz it is 50 frames a second
    int tick_lock = 0;          // the Vita: display.tick_lock = auto | 60 | 30 -- the FRAME RATE row is the TICK LOCK there
                                // (te_tick_lock.h): 0 auto (60/30, the default), 1 60, 2 30; the presenter stays at 60 (every
                                // picture shown) and frame_rate is 1
    bool interp = false;        // video.interp: at 120 / 144 / 240 / uncapped, the frames between two pictures interpolated (on)
                                // or the newest picture shown until the next (off, the default: every frame a picture as the
                                // game drew it -- an interpolated frame re-projects the newest picture's geometry, cut to its
                                // camera, and a fast camera turn shows its gaps); TE_GFX_INTERP overrides it
    bool shadows_on_crates = true;   // video.shadows_on_crates: the shadows fall on the tops of the crates (on, the default:
                                     // FBA's floor, graphics_scene.cpp FloorSplitOf) or, as on the PS2, on no crate (off)
    int game_hz = 60;           // game.hz as chosen: 50 (the PAL console's rate) or 60; takes effect at the next start
    int running_hz = 60;        // the game rate this run started with (te::Settings::field_hz)
};

// THE ANDROID MENUS' RULES (applied to what ps2r.ini says, every start; the file keeps its words, the launcher rewrites them once):
// the window is always full screen (immersive: the display mode row is not shown), the picture is never stretched (fit), the
// shape is 4:3 (auto, the TV's) or 16:9 (16:10, 21:9, 32:9 read as 16:9), and the frame rate is 30 or 60 (120 / 144 / 240 /
// uncapped read as 60). Nothing on the other lanes.
inline void ApplyLaneRules(Settings& s) {
#if defined(__ANDROID__) || defined(TE_DEFAULTS_ANDROID)
    s.fullscreen = 1;
    s.fit = 1;                                   // fit (letterbox): 4:3 with bars at the sides, never stretched
    if (s.aspect == 0) s.aspect = 1;             // auto: the TV's 4:3
    else if (s.aspect > 2) s.aspect = 2;         // the wider shapes: 16:9
    if (s.frame_rate > 1) s.frame_rate = 1;      // 120 / 144 / 240 / uncapped: 60
    if (s.frame_rate < 0) s.frame_rate = 1;
#else
    (void)s;
#endif
}

// The runner's settings (before the device; applied when it is created, re-applied on every change).
void Configure(const Settings& settings);
const Settings& Current();
// The title's MoveDisplay (Platform::Graphics::MoveDisplay: an offset between -1 and 1 each way): the picture moved by
// up to 64 frame pixels across and 32 down, persisted as video.custom_offset_x/y.
void MoveDisplay(float x, float y);
// Push the current settings to the device (graphics.cpp calls it when the device exists; no device = remembered).
void Apply();
// The shape the game's widescreen option draws for (Platform::Graphics::WidescreenAspect): the picture's own at 16:10, 21:9
// and 32:9; 0 at auto, 4:3 and 16:9 (a 16:9 TV's: the game draws as on the console).
float WidescreenAspect();
// One line for the log.
std::string Describe();
// The graphic settings a performance window was measured with, in the stable words the launcher's session history reads
// ("preset quality, scale 4x, anisotropy 16x, draw distance long, aspect 16:9, frame rate 60, vsync on"; the Android
// port's [te.android] config line).
std::string PerfConfig();

// The seam's rows (Platform::Graphics::DisplaySetting*): the settings the page shows, in this order.
// RowDrawDistance: the Android port's experimental draw distance (te_port_settings.h); no choices -- not shown -- elsewhere
// RowPreset: Android only -- original / balanced / quality, and custom while the rows differ from all three (display.cpp).
// RowAntiAliasing / RowDithering: Android only (te_port_settings.h). RowAnisotropy: anisotropic filtering, on Android only (off / 2x / 4x / 8x / 16x up to the driver's maximum; hidden without the
// extension)
enum Row : uint32_t { RowAspect = 0, RowScale, RowTextureFilter, RowVSync, RowFrameRate, RowDisplayMode, RowFpsCounter, RowGameRate,
                     RowDrawDistance, RowAnisotropy, RowAntiAliasing, RowDithering, RowPreset, RowCount };
uint32_t Choices(uint32_t row);
std::string RowLabel(uint32_t row);
std::string ChoiceLabel(uint32_t row, uint32_t choice);
uint32_t Value(uint32_t row);
// The player's choice: applied at once and written to ps2r.ini (when the settings name a file).
void Set(uint32_t row, uint32_t choice);
// the fps counter -- on or off (the runner's --fps-counter / ps2r.ini display.fps_counter / the Display row).
bool FpsCounter();
void SetFpsCounter(bool on);
// The frame rate (0..5 as Settings::frame_rate; the names are the engine's PresentPacer's: "30" .. "240", "uncapped") and
// the swap interval in force: uncapped forces vsync off for the session (the vsync row reads "off (uncapped)" and refuses
// a change while uncapped is selected).
int FrameRate();
const char* FrameRateName();
bool EffectiveVSync();
// The GRAPHIC OPTIONS page is open (the game polled a row's value within the last frame): the help line's cue, and the
// line itself in the menu's language ("vsync on: the display refresh caps the frame rate").
bool GraphicsPageOpen();
std::string FrameRateHelp();
// The game rate chosen in the menu differs from the one this run started with: the help line says it applies next start.
bool GameRatePending();
std::string GameRateHelp();
#if defined(__vita__)
// The PS Vita's RESOLUTION row at its maximum (2x) and the help line the page shows then, in the menu's language
// ("2x is the ps vita's maximum:~half the frame rate or less", two lines).
bool ScaleAtMaximum();
std::string ScaleHelp();
#endif

}  // namespace te::display
