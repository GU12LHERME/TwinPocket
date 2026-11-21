// runner/te_android.cpp -- the Android lane's entry point (TE_ANDROID; CMakeLists.txt).
//
// SDL2's Java activity (org.libsdl.app.SDLActivity) loads libSDL2.so and libmain.so and calls SDL_main on its own
// thread. This file is that SDL_main: it sends the program's stdout / stderr (where the runner and the engine write
// their log lines) to logcat, gives the game the app's own folders and enters the runner's main(), which this lane
// compiles as te_runner_main (CMakeLists.txt: runner/main.cpp's COMPILE_DEFINITIONS).
//
// The folders: the app-specific external storage directory (SDL_AndroidGetExternalStoragePath:
// /storage/emulated/0/Android/data/<package>/files) is HOME and the working directory, so the runner's own lookups
// (te_startup.cpp: <home>/.twinsanityevolved for ps2r.ini and the memory cards) land inside the app's storage. The
// disc image is the user's own file, never part of the package: the first of
//   1. the path on the first line of <files>/disc-image.txt,
//   2. <files>/twinsanity.iso,
//   3. the first *.iso in <files>,
// is passed as --iso. Without one the runner starts as on the desktop without a disc (and says so in the log).
// A picker through Android's document UI and the in-app settings are later stages of the Android plan.
#include <SDL.h>
#include <SDL_system.h>

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

int te_runner_main(int argc, char** argv);   // runner/main.cpp, renamed for this lane

namespace {

constexpr const char* kTag = "TwinsanityEvolved";

int g_log_pipe[2] = {-1, -1};

// stdout and stderr into one pipe, its lines into logcat (the runner and the engine log with printf / fprintf)
void* LogPump(void*) {
    char buf[1024];
    std::string line;
    for (;;) {
        const ssize_t n = ::read(g_log_pipe[0], buf, sizeof buf);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; ++i) {
            if (buf[i] == '\n') {
                __android_log_write(ANDROID_LOG_INFO, kTag, line.c_str());
                line.clear();
            } else {
                line.push_back(buf[i]);
            }
        }
    }
    if (!line.empty()) __android_log_write(ANDROID_LOG_INFO, kTag, line.c_str());
    return nullptr;
}

void RedirectStdioToLogcat() {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::setvbuf(stderr, nullptr, _IOLBF, 0);
    if (::pipe(g_log_pipe) != 0) return;
    ::dup2(g_log_pipe[1], STDOUT_FILENO);
    ::dup2(g_log_pipe[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, &LogPump, nullptr) == 0) pthread_detach(thread);
}

std::string FirstLine(const std::filesystem::path& file) {
    std::ifstream f(file, std::ios::binary);
    std::string line;
    if (!f || !std::getline(f, line)) return {};
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) line.pop_back();
    return line;
}

std::string FindDiscImage(const std::filesystem::path& files) {
    namespace fs = std::filesystem;
    std::error_code ec;
    const std::string named = FirstLine(files / "disc-image.txt");
    if (!named.empty() && fs::is_regular_file(named, ec)) return named;
    if (fs::is_regular_file(files / "twinsanity.iso", ec)) return (files / "twinsanity.iso").string();
    for (fs::directory_iterator it(files, ec), end; !ec && it != end; it.increment(ec)) {
        if (!it->is_regular_file(ec)) continue;
        std::string ext = it->path().extension().string();
        for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        if (ext == ".iso") return it->path().string();
    }
    return {};
}

}  // namespace

extern "C" __attribute__((visibility("default"))) int SDL_main(int argc, char** argv) {
    RedirectStdioToLogcat();
    std::printf("[te.android] TwinsanityEvolved for Android (arm64-v8a) starting\n");

    const char* external = SDL_AndroidGetExternalStoragePath();
    const char* internal = SDL_AndroidGetInternalStoragePath();
    const std::string files = external && *external ? external : internal && *internal ? internal : "";
    if (!files.empty()) {
        std::error_code ec;
        std::filesystem::create_directories(files, ec);
        ::setenv("HOME", files.c_str(), 1);
        if (::chdir(files.c_str()) != 0) std::printf("[te.android] chdir(%s) failed\n", files.c_str());
        std::printf("[te.android] the app's folder: %s\n", files.c_str());
    } else {
        std::printf("[te.android] no app storage folder from SDL (%s)\n", SDL_GetError());
    }

    std::vector<std::string> args;
    args.emplace_back(argc > 0 && argv && argv[0] ? argv[0] : "twinsanityevolved");
    for (int i = 1; i < argc; ++i) args.emplace_back(argv[i]);
    const std::string iso = files.empty() ? std::string() : FindDiscImage(files);
    if (!iso.empty()) {
        args.emplace_back("--iso");
        args.push_back(iso);
        std::printf("[te.android] disc image: %s\n", iso.c_str());
    } else {
        std::printf("[te.android] no disc image: put your own Crash Twinsanity image (SLES-52568 or SLUS-20909 v2.00) in %s "
                    "as twinsanity.iso, or its path in disc-image.txt there\n", files.c_str());
    }
    std::vector<char*> cargs;
    for (std::string& a : args) cargs.push_back(a.data());
    cargs.push_back(nullptr);
    const int rc = te_runner_main(static_cast<int>(args.size()), cargs.data());
    std::printf("[te.android] the runner returned %d\n", rc);
    std::fflush(stdout);
    std::fflush(stderr);
    return rc;
}
