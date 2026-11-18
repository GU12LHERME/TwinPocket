#include "engine/platform/android/platform_android.h"

#include <cerrno>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include "engine/platform/host/platform.h"
#include "engine/platform/platform.h"

namespace ps2r::platform::android {
namespace {

std::mutex g_root_mutex;
std::string g_private_root;

void LogLine(const char* message) {
#if defined(__ANDROID__)
    __android_log_write(ANDROID_LOG_INFO, "NativeEE", message);
#else
    std::fprintf(stderr, "NativeEE: %s\n", message);
#endif
}

struct ThreadStart {
    void (*fn)(void*);
    void* arg;
    std::string name;
    bool interactive;
};

void ThreadEntry(void* opaque) {
    auto* start = static_cast<ThreadStart*>(opaque);
    ThreadStart value = std::move(*start);
    delete start;
    if (!value.name.empty()) ps2r::host::SetCurrentThreadName(value.name.c_str());
    if (value.interactive) ps2r::host::PinCurrentThreadInteractive();
    value.fn(value.arg);
}

platform::File OpenPrivate(const char* relative, FileMode mode) {
    if (!relative || !*relative || relative[0] == '/') { errno = EINVAL; return {}; }
    std::vector<std::string> parts;
    std::string path(relative);
    size_t begin = 0;
    while (begin <= path.size()) {
        const size_t end = path.find('/', begin);
        const std::string part = path.substr(begin, end == std::string::npos ? end : end - begin);
        if (part.empty() || part == "." || part == "..") { errno = EINVAL; return {}; }
        parts.push_back(part);
        if (end == std::string::npos) break;
        begin = end + 1;
    }

    std::string root;
    {
        std::lock_guard<std::mutex> lock(g_root_mutex);
        root = g_private_root;
    }
    if (root.empty()) { errno = ENOENT; return {}; }
    int dir = ::open(root.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dir < 0) return {};
    for (size_t i = 0; i + 1 < parts.size(); ++i) {
        int next = ::openat(dir, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (next < 0 && errno == ENOENT && mode != FileMode::Read) {
            if (::mkdirat(dir, parts[i].c_str(), 0700) == 0 || errno == EEXIST)
                next = ::openat(dir, parts[i].c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        }
        ::close(dir);
        if (next < 0) return {};
        dir = next;
    }

    int flags = O_CLOEXEC | O_NOFOLLOW;
    const char* fd_mode = "r";
    if (mode == FileMode::Write) { flags |= O_WRONLY | O_CREAT | O_TRUNC; fd_mode = "w"; }
    else if (mode == FileMode::Append) { flags |= O_WRONLY | O_CREAT | O_APPEND; fd_mode = "a"; }
    else flags |= O_RDONLY;
    const int fd = ::openat(dir, parts.back().c_str(), flags, 0600);
    ::close(dir);
    if (fd < 0) return {};
    std::FILE* file = ::fdopen(fd, fd_mode);
    if (!file) ::close(fd);
    return platform::File{file};
}

platform::Thread CreateThread(void (*fn)(void*), void* arg, const platform::ThreadDesc& desc) {
    if (!fn) return {};
    auto* start = new ThreadStart{fn, arg, desc.name ? desc.name : "", desc.interactive_qos};
    ps2r::host::NativeThread thread;
    if (!ps2r::host::ThreadCreate(&thread, ThreadEntry, start, desc.stack)) {
        delete start;
        return {};
    }
    return platform::Thread{thread.handle};
}

void JoinThread(platform::Thread thread) {
    ps2r::host::ThreadJoin(ps2r::host::NativeThread{thread.handle});
}

void CpuRelax() {
#if defined(__aarch64__) || defined(__arm__)
    asm volatile("yield" ::: "memory");
#else
    std::atomic_signal_fence(std::memory_order_seq_cst);
#endif
}

uint64_t NowNs() { return ps2r::host::MonotonicNowNs(); }

platform::Platform g_platform = [] {
    platform::Platform p;
    p.open = OpenPrivate;
    p.thread_create = CreateThread;
    p.thread_join = JoinThread;
    p.now_ns = NowNs;
    p.cpu_relax = CpuRelax;
    p.page_size = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
    p.caps.name = "android-arm64-bootstrap";
    p.caps.memory_budget_mb = 0;  // queried later from the Android application context
    p.caps.gles_level = 0;
    p.caps.has_fibers = false;
    p.caps.has_mmap = false;
    p.caps.has_window = false;
    p.caps.has_audio = false;
    return p;
}();

}  // namespace

bool Initialize(const char* private_files_root) {
    if (!private_files_root || !*private_files_root) return false;
    const int fd = ::open(private_files_root, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return false;
    ::close(fd);
    {
        std::lock_guard<std::mutex> lock(g_root_mutex);
        g_private_root = private_files_root;
    }
    platform::Register(&g_platform);
    LogLine("NativeEE Android bootstrap initialized");
    return true;
}

void Shutdown() {
    platform::Register(nullptr);
    {
        std::lock_guard<std::mutex> lock(g_root_mutex);
        g_private_root.clear();
    }
    LogLine("NativeEE Android bootstrap stopped");
}

}  // namespace ps2r::platform::android
