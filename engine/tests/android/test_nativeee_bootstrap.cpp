#include <atomic>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include "engine/core/config/config.h"
#include "engine/core/log/logger.h"
#include "engine/core/log/ring_log.h"
#include "engine/platform/android/platform_android.h"
#include "engine/platform/platform.h"

namespace {
void ThreadWork(void* value) { static_cast<std::atomic<int>*>(value)->store(42); }
}

int main() {
    assert(!ps2r::platform::Registered());
    char temp[] = "/tmp/nativeee-android-bootstrap-XXXXXX";
    char* root = ::mkdtemp(temp);
    assert(root);
    assert(ps2r::platform::android::Initialize(root));
    assert(ps2r::platform::Registered());
    const auto& p = ps2r::platform::Current();
    assert(std::strcmp(p.caps.name, "android-arm64-bootstrap") == 0);
    assert(!p.caps.has_fibers && !p.caps.has_window && !p.caps.has_audio && !p.caps.has_mmap);
    assert(p.now_ns && p.now_ns() != 0);
    const uint64_t before = p.now_ns();
    assert(p.now_ns() >= before);
    assert(p.page_size > 0);

    auto file = p.open("nested/bootstrap.log", ps2r::platform::FileMode::Write);
    assert(file.fp);
    std::fputs("nativeee-core-ok\n", file.fp);
    std::fclose(file.fp);
    assert(!p.open("../outside", ps2r::platform::FileMode::Write).fp);
    assert(!p.open("/absolute", ps2r::platform::FileMode::Write).fp);

    std::atomic<int> result{0};
    auto thread = p.thread_create(ThreadWork, &result, ps2r::platform::ThreadDesc{"bootstrap-test", false, 0});
    assert(thread.handle);
    p.thread_join(thread);
    assert(result.load() == 42);

    ps2r::log::Level level{};
    assert(ps2r::log::LevelFromName("info", &level));
    assert(level == ps2r::log::Level::Info);
    const auto& keys = ps2r::Config::Keys();
    assert(!keys.empty() && ps2r::Config::IsKey(keys.front()));
    assert(ps2r::ringlog::RingLog::ParseChannelList("none", 123u) == 0u);

    ps2r::log::Logger::Instance().Start(std::string(root) + "/nativeee.log", ps2r::log::Level::Info, false);
    ps2r::log::Logger::Instance().Logf(ps2r::log::Category::HOST, ps2r::log::Level::Info, "bootstrap %s", "ready");
    ps2r::log::Logger::Instance().Flush();
    assert(ps2r::log::Logger::Instance().LinesWritten() == 1);
    ps2r::log::Logger::Instance().Stop();

    ps2r::platform::android::Shutdown();
    assert(!ps2r::platform::Registered());
    std::remove((std::string(root) + "/nested/bootstrap.log").c_str());
    std::remove((std::string(root) + "/nativeee.log").c_str());
    ::rmdir((std::string(root) + "/nested").c_str());
    ::rmdir(root);
    return 0;
}
