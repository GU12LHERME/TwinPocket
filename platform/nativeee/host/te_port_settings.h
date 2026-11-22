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

// The rows of the page, in order
enum Row : uint32_t { RowAudioDriver = 0, RowAudioBuffer, RowStickDeadzone, RowStickSensitivity, RowRestoreDefaults, RowCount };

// The settings file's text (ps2r.ini as the runner read it; "" without one): the rows' values, and the sticks' response
// handed to the input module (te::input::SetStickResponse) for its Install.
void Configure(const std::string& ini_text);
// The page's rows: RowCount on Android, 0 elsewhere
uint32_t Count();
// The page's title on the options page ("android settings")
std::string Title();
uint32_t Choices(uint32_t row);
std::string RowLabel(uint32_t row);
std::string ChoiceLabel(uint32_t row, uint32_t choice);
uint32_t Value(uint32_t row);
// The player's choice: applied at once (the sticks) or at the next start (the audio: the label says so), written to ps2r.ini
void Set(uint32_t row, uint32_t choice);
// One line for the log
std::string Describe();

}  // namespace te::port_settings
