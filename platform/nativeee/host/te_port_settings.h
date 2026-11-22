// te_port_settings.h -- the platform's OWN settings page in the title's Options menu (Options > "android settings"): the
// Android port's settings that are not the graphic options' -- the audio driver and buffer, the sticks' deadzone and
// sensitivity, and a row that restores the defaults. Shown through the same seam rows as the graphic options
// (Platform::Graphics::DisplaySetting*, page 1: display.cpp dispatches the rows past its own), persisted in ps2r.ini as
// `android.*` keys -- the ONE settings file the game and the Android launcher both read and write (the launcher's
// "Configurações" edit the same keys; runner/te_android.cpp reads the audio keys before SDL starts).
//
// On every other lane the page has no rows (Count() == 0): the options page shows no link and nothing changes.
#pragma once
#include <cstdint>
#include <string>

namespace te::port_settings {

// The keys and their values (the launcher writes the same words: android/game/.../Settings.java)
//   android.audio_driver   = openslES (default) | aaudio | android | auto        SDL's Android audio driver, next start
//   android.audio_buffer   = 1024 | 2048 (default) | 4096                         frames a callback, next start
//   android.stick_deadzone = 0.05 | 0.10 | 0.15 (default) | 0.20 | 0.25          at once
//   android.stick_sensitivity = 1.00 | 1.33 (default) | 1.66 | 2.00              at once
//   android.draw_distance  = original (default) | medium | long | max (0.8.2's extreme / ultra read as max)           at once (the graphic options' row; 0.5-0.6's
//                            100 / 150 / 200 / 300 read as original / medium / medium / long)

// The rows of the page, in order
// RowReturnToLauncher: an action -- after a second press the session ends the way a desktop window's close button ends it
// (an SDL_QUIT: the window pump's quit -> te::RequestExit(0)); SDL_main returns, the :game activity finishes and the
// launcher is back. Saves are written only where the game saves; nothing is killed.
enum Row : uint32_t { RowAudioDriver = 0, RowAudioBuffer, RowStickDeadzone, RowStickSensitivity, RowRestoreDefaults, RowReturnToLauncher,
                      RowCount };

// The settings file's text (ps2r.ini as the runner read it; "" without one): the rows' values, and the sticks' response
// handed to the input module (te::input::SetStickResponse) for its Install.
void Configure(const std::string& ini_text);
// The page's rows: RowCount on Android, 0 elsewhere
uint32_t Count();
// The page's title on the options page ("android settings")
std::string Title();
uint32_t Choices(uint32_t row);
// 0 a choice row, 1 an action (restore defaults, return to launcher: one entry, a second press within ~5 s confirms; its label
// says so)
uint32_t Kind(uint32_t row);
std::string RowLabel(uint32_t row);
std::string ChoiceLabel(uint32_t row, uint32_t choice);
uint32_t Value(uint32_t row);
// The player's choice: applied at once (the sticks) or at the next start (the audio: the label says so), written to ps2r.ini
void Set(uint32_t row, uint32_t choice);
// One line for the log
std::string Describe();

// THE DRAW DISTANCE (experimental; the graphic options' row on Android, te_display.h RowDrawDistance): the levels of detail
// at 100 / 200 / 300 / 400 / 600 / 800 % of the game's distances (te_lod_policy.h: the choice is made in the module's LodModelAt, among
// the meshes already loaded). Only on Android; every other lane keeps the game's own choice.
uint32_t DrawDistanceChoices();                 // 4 on Android (original, medium, long, max), 0 elsewhere
uint32_t DrawDistanceChoice();
std::string DrawDistanceLabel(uint32_t choice);
void SetDrawDistance(uint32_t choice);

}  // namespace te::port_settings
