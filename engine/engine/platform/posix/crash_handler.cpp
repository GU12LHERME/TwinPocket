// platform/posix/crash_handler.cpp -- execinfo backtrace + sigaction; identical on Darwin and glibc.
#include "engine/platform/posix/crash_handler.h"

#if defined(__ANDROID__) && __ANDROID_API__ < 33
// bionic has backtrace() / backtrace_symbols_fd() from API 33 only: below it the frames come from the unwinder and are
// written as addresses (debuggerd's tombstone in logcat symbolises the same crash).
#include <unwind.h>
#define PS2R_CRASH_UNWIND 1
#else
#include <execinfo.h>
#endif
#include <signal.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>

#include "engine/core/log/ring_log.h"

namespace ps2r::platform {

namespace {
bool g_installed = false;
char g_ring_path[512] = {0};
const int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};

void WriteStr(int fd, const char* s) { (void)!write(fd, s, std::strlen(s)); }
}  // namespace

#if defined(PS2R_CRASH_UNWIND)
namespace {
struct UnwindFrames {
    void** frames;
    int    count;
    int    max;
};
_Unwind_Reason_Code CollectFrame(struct _Unwind_Context* ctx, void* opaque) {
    auto* u = static_cast<UnwindFrames*>(opaque);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc && u->count < u->max) u->frames[u->count++] = reinterpret_cast<void*>(pc);
    return u->count < u->max ? _URC_NO_REASON : _URC_END_OF_STACK;
}
}  // namespace

void CrashHandler::WriteBacktrace(int fd, int skip_frames) {
    void* frames[64];
    UnwindFrames u{frames, 0, 64};
    _Unwind_Backtrace(&CollectFrame, &u);
    static const char kHex[] = "0123456789abcdef";
    for (int i = skip_frames; i < u.count; ++i) {
        char line[24];
        uintptr_t v = reinterpret_cast<uintptr_t>(frames[i]);
        int p = 0;
        line[p++] = '0';
        line[p++] = 'x';
        for (int shift = 60; shift >= 0; shift -= 4) line[p++] = kHex[(v >> shift) & 0xF];
        line[p++] = '\n';
        line[p] = 0;
        WriteStr(fd, line);
    }
}
#else
void CrashHandler::WriteBacktrace(int fd, int skip_frames) {
    void* frames[64];
    const int n = backtrace(frames, 64);
    if (n > skip_frames) backtrace_symbols_fd(frames + skip_frames, n - skip_frames, fd);
}
#endif

void CrashHandler::OnSignal(int sig) {
    WriteStr(2, "\n=== fatal signal ");
    WriteStr(2, sig == SIGSEGV ? "SIGSEGV" : sig == SIGBUS ? "SIGBUS" : sig == SIGILL ? "SIGILL"
             : sig == SIGFPE ? "SIGFPE" : sig == SIGABRT ? "SIGABRT" : "?");
    WriteStr(2, " ===\n");
    WriteBacktrace(2, 2);
    if (g_ring_path[0]) ringlog::RingLog::DumpAllToPath(g_ring_path, ringlog::DUMP_CRASH);
    signal(sig, SIG_DFL);
    raise(sig);
}

void CrashHandler::Install(const char* ring_dump_path) {
    if (ring_dump_path) {
        std::strncpy(g_ring_path, ring_dump_path, sizeof(g_ring_path) - 1);
        g_ring_path[sizeof(g_ring_path) - 1] = 0;
    }
    if (g_installed) return;
    g_installed = true;
    struct sigaction sa;
    std::memset(&sa, 0, sizeof(sa));
    sa.sa_handler = &CrashHandler::OnSignal;
    sa.sa_flags = SA_RESETHAND | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    for (int s : kSignals) sigaction(s, &sa, nullptr);
}

bool CrashHandler::Installed() { return g_installed; }

}  // namespace ps2r::platform
