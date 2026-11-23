// runner/te_android_diag.h -- the Android lane's start-up diagnostics (runner/te_android_diag.cpp).
//
// Every start-up stage is a line in logcat (tag TwinsanityPocket) and in a log file in the app's private folder
// (/data/data/<package>/files/logs/latest-<process>.log), and the last stage reached is kept in
// files/logs/stage-<process>.txt, written synchronously: a process that dies without a word is located by that file,
// and a fatal signal adds its own line to both. The launcher (android/game: LauncherActivity) shows them and shares them.
// On the other lanes TE_ANDROID_STAGE is nothing.
#pragma once

#if defined(__ANDROID__)
#include <string>

extern "C" {
// A start-up stage reached: logged, and the stage file rewritten (synced).
void te_android_stage(const char* what);
// One log line (logcat + the log file).
void te_android_log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
// The folder of the log files (files/logs), empty if it could not be made.
const char* te_android_log_dir(void);
// An alternate signal stack for the calling thread (a stack overflow still reaches the crash handler).
void te_android_thread_altstack(void);
// stdout / stderr into logcat and the log file (the runner and the engine log with printf / fprintf).
void te_android_redirect_stdio(void);
// The process's end recorded in the stage file (a clean return, not a crash).
void te_android_finished(int code);
}

namespace te::android {
// The user's disc image in the app's folder: <dir>/disc-image.txt's first line, <dir>/twinsanity.iso, then the first
// *.iso there. *how says which (or why none); empty when there is none.
std::string FindDiscImage(const std::string& dir, std::string* how);
// Whether `path` opens and reads as a PS2 DVD image (ISO 9660 "CD001" at sector 16, 2048- or 2352-byte sectors);
// *report is a one-line description (size, layout, volume id, or the error).
bool CheckDiscImage(const std::string& path, std::string* report);
}  // namespace te::android

#define TE_ANDROID_STAGE(what) te_android_stage(what)
#else
#define TE_ANDROID_STAGE(what) ((void)0)
#endif
