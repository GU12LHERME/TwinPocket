// runner/te_android_diag.cpp -- the Android lane's start-up diagnostics (te_android_diag.h), the probe the launcher's
// diagnostic mode runs without the game (TeDiag.nativeProbe), and the stdout / stderr pump into logcat and the log file.
#include "te_android_diag.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "engine/platform/posix/fiber_posix.h"
#include "engine/render/backends/gl/gl_shader_sources.h"

namespace {

constexpr const char* kTag = "TwinsanityEvolved";

char g_dir[512];          // files/logs
char g_proc[64] = "main"; // the process's suffix (":game" -> "game", the main process "main")
char g_stage_path[600];
int g_log_fd = -1;
std::atomic<int> g_stage_num{0};
char g_stage_name[200] = "library loaded";
int g_log_pipe[2] = {-1, -1};

// --- async-signal-safe helpers ------------------------------------------------------------------------------------------
void SafeWrite(int fd, const char* s) {
    if (fd < 0 || !s) return;
    size_t n = std::strlen(s);
    while (n > 0) {
        const ssize_t w = ::write(fd, s, n);
        if (w <= 0) return;
        s += w;
        n -= static_cast<size_t>(w);
    }
}
void SafeNum(char* out, long long v) {   // decimal into out (>= 24 bytes)
    char tmp[24];
    int n = 0;
    const bool neg = v < 0;
    unsigned long long u = neg ? static_cast<unsigned long long>(-v) : static_cast<unsigned long long>(v);
    do { tmp[n++] = static_cast<char>('0' + u % 10); u /= 10; } while (u && n < 22);
    int p = 0;
    if (neg) out[p++] = '-';
    while (n) out[p++] = tmp[--n];
    out[p] = 0;
}
void SafeHex(char* out, uintptr_t v) {
    static const char kHex[] = "0123456789abcdef";
    int p = 0;
    out[p++] = '0';
    out[p++] = 'x';
    for (int s = 60; s >= 0; s -= 4) out[p++] = kHex[(v >> s) & 0xF];
    out[p] = 0;
}

void WriteStageFile(const char* state, int num, const char* name) {
    if (!g_stage_path[0]) return;
    const int fd = ::open(g_stage_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char n[24];
    SafeNum(n, num);
    SafeWrite(fd, state);
    SafeWrite(fd, " ");
    SafeWrite(fd, n);
    SafeWrite(fd, " ");
    SafeWrite(fd, name);
    SafeWrite(fd, "\n");
    ::fsync(fd);
    ::close(fd);
}

const char* SignalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (invalid memory access)";
        case SIGBUS:  return "SIGBUS (bus error)";
        case SIGILL:  return "SIGILL (illegal instruction)";
        case SIGFPE:  return "SIGFPE (arithmetic)";
        case SIGABRT: return "SIGABRT (abort)";
        case SIGTRAP: return "SIGTRAP (trap)";
        default:      return "signal";
    }
}

const int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP};
struct sigaction g_old[32];

void OnFatalSignal(int sig, siginfo_t* info, void* /*ucontext*/) {
    char line[512];
    line[0] = 0;
    char num[24], addr[24], stage[24];
    SafeNum(num, sig);
    SafeHex(addr, reinterpret_cast<uintptr_t>(info ? info->si_addr : nullptr));
    SafeNum(stage, g_stage_num.load());
    std::strcpy(line, "FATAL ");
    std::strcat(line, SignalName(sig));
    std::strcat(line, " [");
    std::strcat(line, num);
    std::strcat(line, "] fault address ");
    std::strcat(line, addr);
    std::strcat(line, " during stage ");
    std::strcat(line, stage);
    std::strcat(line, ": ");
    std::strncat(line, g_stage_name, sizeof line - std::strlen(line) - 2);
    __android_log_write(ANDROID_LOG_FATAL, kTag, line);
    SafeWrite(g_log_fd, line);
    SafeWrite(g_log_fd, "\n");
    if (g_log_fd >= 0) ::fsync(g_log_fd);
    char state[96];
    std::strcpy(state, "crash ");
    std::strcat(state, SignalName(sig));
    std::strcat(state, " at");
    WriteStageFile(state, g_stage_num.load(), g_stage_name);
    // hand the signal on to the system's handler (debuggerd: the tombstone and the "Fatal signal" logcat lines)
    sigaction(sig, &g_old[sig], nullptr);
    if (!info || info->si_code <= 0) raise(sig);   // sent (abort / kill): send it again; a fault re-faults on return
}

void InstallSignalHandlers() {
    struct sigaction sa;
    std::memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = &OnFatalSignal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (int s : kSignals) sigaction(s, &sa, &g_old[s]);
}

void LogPumpLine(const std::string& line) {
    __android_log_write(ANDROID_LOG_INFO, kTag, line.c_str());
    if (g_log_fd >= 0) {
        std::string l = line;
        l.push_back('\n');
        SafeWrite(g_log_fd, l.c_str());
    }
}

void* LogPump(void*) {
    char buf[2048];
    std::string line;
    for (;;) {
        const ssize_t n = ::read(g_log_pipe[0], buf, sizeof buf);
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; ++i) {
            if (buf[i] == '\n') { LogPumpLine(line); line.clear(); }
            else line.push_back(buf[i]);
        }
    }
    if (!line.empty()) LogPumpLine(line);
    return nullptr;
}

std::string TimeStamp() {
    char buf[64];
    const time_t t = time(nullptr);
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(buf, sizeof buf, "%Y-%m-%d %H:%M:%S", &tm);
    return buf;
}

std::string Prop(const char* name) {
    char v[PROP_VALUE_MAX] = {0};
    __system_property_get(name, v);
    return v;
}

// Runs when libmain.so is loaded, before the C++ objects of the engine and the game (priority 101): the log file, the
// stage file and the crash handler are in place before anything else of the library runs.
__attribute__((constructor(101))) void EarlyInit() {
    char cmdline[256] = {0};
    const int fd = ::open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { (void)!::read(fd, cmdline, sizeof cmdline - 1); ::close(fd); }
    std::string pkg = cmdline;
    const size_t colon = pkg.find(':');
    if (colon != std::string::npos) {
        std::snprintf(g_proc, sizeof g_proc, "%s", pkg.substr(colon + 1).c_str());
        pkg = pkg.substr(0, colon);
    }
    if (!pkg.empty() && pkg.find('/') == std::string::npos) {
        const std::string files = "/data/data/" + pkg + "/files";
        ::mkdir(files.c_str(), 0700);
        const std::string dir = files + "/logs";
        ::mkdir(dir.c_str(), 0700);
        std::snprintf(g_dir, sizeof g_dir, "%s", dir.c_str());
        const std::string latest = dir + "/latest-" + g_proc + ".log";
        const std::string previous = dir + "/previous-" + g_proc + ".log";
        ::rename(latest.c_str(), previous.c_str());
        g_log_fd = ::open(latest.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0600);
        std::snprintf(g_stage_path, sizeof g_stage_path, "%s/stage-%s.txt", dir.c_str(), g_proc);
    }
    InstallSignalHandlers();
    te_android_log("TwinsanityEvolved native library loaded: process %s (pid %d), %s, Android %s (API %s), %s %s, page size %ld",
                   cmdline, static_cast<int>(::getpid()), TimeStamp().c_str(), Prop("ro.build.version.release").c_str(),
                   Prop("ro.build.version.sdk").c_str(), Prop("ro.product.manufacturer").c_str(), Prop("ro.product.model").c_str(),
                   ::sysconf(_SC_PAGESIZE));
    te_android_log("build: %s %s, clang %s, built for API %d, log folder %s", __DATE__, __TIME__, __clang_version__,
                   __ANDROID_API__, g_dir[0] ? g_dir : "(none)");
    te_android_stage("native library loaded (static initialisation)");
}

}  // namespace

// --- the C entry points (te_android_diag.h) -----------------------------------------------------------------------------
extern "C" void te_android_log(const char* fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_write(ANDROID_LOG_INFO, kTag, buf);
    if (g_log_fd >= 0) {
        const std::string line = "[" + TimeStamp() + "] " + buf + "\n";
        SafeWrite(g_log_fd, line.c_str());
    }
}

extern "C" void te_android_stage(const char* what) {
    const int n = ++g_stage_num;
    std::snprintf(g_stage_name, sizeof g_stage_name, "%s", what ? what : "?");
    te_android_log("[stage %d] %s", n, g_stage_name);
    if (g_log_fd >= 0) ::fsync(g_log_fd);
    WriteStageFile("running", n, g_stage_name);
}

extern "C" void te_android_finished(int code) {
    char name[256];
    std::snprintf(name, sizeof name, "returned %d (last stage: %s)", code, g_stage_name);
    te_android_log("the native program ended normally: %s", name);
    WriteStageFile("finished", g_stage_num.load(), name);
}

extern "C" const char* te_android_log_dir(void) { return g_dir; }

extern "C" void te_android_thread_altstack(void) {
    static thread_local std::vector<char> stack;
    if (!stack.empty()) return;
    stack.resize(64 * 1024);
    stack_t ss{};
    ss.ss_sp = stack.data();
    ss.ss_size = stack.size();
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);
}

// stdout / stderr -> one pipe -> logcat + the log file (the runner and the engine log with printf / fprintf)
extern "C" void te_android_redirect_stdio(void) {
    if (g_log_pipe[0] >= 0) return;
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::setvbuf(stderr, nullptr, _IOLBF, 0);
    if (::pipe(g_log_pipe) != 0) return;
    ::dup2(g_log_pipe[1], STDOUT_FILENO);
    ::dup2(g_log_pipe[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, &LogPump, nullptr) == 0) pthread_detach(thread);
}

// exit() called from the runner or the engine (CMakeLists.txt links libmain.so with -Wl,--wrap=exit): logged with its
// status and the stage it ended, so a "closes at once" start is told apart from a crash.
extern "C" void __real_exit(int code) __attribute__((noreturn));
extern "C" void __wrap_exit(int code) {
    char name[256];
    std::snprintf(name, sizeof name, "exit(%d) called (last stage: %s)", code, g_stage_name);
    te_android_log("%s", name);
    std::fflush(stdout);
    std::fflush(stderr);
    WriteStageFile("exited", g_stage_num.load(), name);
    __real_exit(code);
}

// --- the disc image -----------------------------------------------------------------------------------------------------
namespace te::android {

namespace {
std::string FirstLine(const std::filesystem::path& file) {
    std::ifstream f(file, std::ios::binary);
    std::string line;
    if (!f || !std::getline(f, line)) return {};
    while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) line.pop_back();
    return line;
}
}  // namespace

std::string FindDiscImage(const std::string& dir, std::string* how) {
    namespace fs = std::filesystem;
    std::error_code ec;
    const fs::path files(dir);
    const std::string named = FirstLine(files / "disc-image.txt");
    if (!named.empty()) {
        if (fs::is_regular_file(named, ec)) { if (how) *how = "named by disc-image.txt"; return named; }
        if (how) *how = "disc-image.txt names " + named + ", which is not a readable file here";
    }
    if (fs::is_regular_file(files / "twinsanity.iso", ec)) { if (how) *how = "twinsanity.iso"; return (files / "twinsanity.iso").string(); }
    for (fs::directory_iterator it(files, ec), end; !ec && it != end; it.increment(ec)) {
        if (!it->is_regular_file(ec)) continue;
        std::string ext = it->path().extension().string();
        for (char& c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        if (ext == ".iso") { if (how) *how = "the first .iso in the folder"; return it->path().string(); }
    }
    if (how && how->empty()) *how = "no twinsanity.iso, no .iso file and no disc-image.txt in " + dir;
    return {};
}

bool CheckDiscImage(const std::string& path, std::string* report) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (report) *report = "cannot open " + path + ": " + std::strerror(errno);
        return false;
    }
    struct stat st{};
    ::fstat(fd, &st);
    struct Layout { long sector; long offset; const char* name; };
    const Layout layouts[] = {{2048, 0, "2048-byte sectors (.iso)"}, {2352, 16, "2352-byte raw, mode 1"}, {2352, 24, "2352-byte raw, mode 2"}};
    bool ok = false;
    std::string found;
    for (const Layout& l : layouts) {
        unsigned char pvd[2048];
        const off_t at = static_cast<off_t>(16 * l.sector + l.offset);
        if (::pread(fd, pvd, sizeof pvd, at) != static_cast<ssize_t>(sizeof pvd)) continue;
        if (pvd[0] == 1 && std::memcmp(pvd + 1, "CD001", 5) == 0) {
            std::string vol(reinterpret_cast<const char*>(pvd + 40), 32);
            while (!vol.empty() && vol.back() == ' ') vol.pop_back();
            found = std::string(l.name) + ", volume \"" + vol + "\"";
            ok = true;
            break;
        }
    }
    ::close(fd);
    char size[64];
    std::snprintf(size, sizeof size, "%.1f MB", static_cast<double>(st.st_size) / 1048576.0);
    if (report) *report = path + " (" + size + "): " + (ok ? "ISO 9660 " + found : std::string("no ISO 9660 volume descriptor at sector 16 (not a PS2 DVD image?)"));
    return ok;
}

}  // namespace te::android

// --- the diagnostic probe (the launcher's diagnostic mode: no game, no SDL) -----------------------------------------------
namespace {

std::string g_probe;
void P(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void P(const char* fmt, ...) {
    char buf[4096];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    te_android_log("[probe] %s", buf);
    g_probe += buf;
    g_probe += "\n";
}

// the engine's fibers (posix/fiber_android_arm64.cpp): two fibers and back, callee-saved state kept
ps2r::platform::Fiber g_main_fiber, g_fiber_a;
int g_fiber_count = 0;
void FiberBody(void*) {
    for (;;) {
        ++g_fiber_count;
        ps2r::platform::FiberPosix::Switch(g_main_fiber);
    }
}

std::string ShaderLog(GLuint s) {
    GLint n = 0;
    glGetShaderiv(s, GL_INFO_LOG_LENGTH, &n);
    std::string log(static_cast<size_t>(n > 1 ? n : 1), '\0');
    if (n > 1) glGetShaderInfoLog(s, n, nullptr, log.data());
    if (!log.empty() && log.back() == '\0') log.pop_back();
    if (log.size() > 600) log = log.substr(0, 600) + " ...";
    return log;
}

bool Compile(GLenum type, const std::string& src, std::string* log) {
    const GLuint s = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (log) *log = ShaderLog(s);
    glDeleteShader(s);
    return ok != 0;
}

// the engine's GLSL 4.10 text with only its #version line changed to GLSL ES 3.20 (what stage 3 has to do properly)
std::string AsEs(const char* source, bool fragment) {
    std::string s = source;
    const size_t v = s.find("#version");
    if (v != std::string::npos) {
        const size_t eol = s.find('\n', v);
        s.replace(v, eol - v, "#version 320 es");
    }
    const size_t eol = s.find('\n', s.find("#version 320 es"));
    s.insert(eol + 1, fragment ? "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
                               : "precision highp float;\nprecision highp int;\n");
    return s;
}

void ProbeGl() {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major = 0, minor = 0;
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, &major, &minor)) { P("EGL: eglInitialize FAILED (0x%x)", eglGetError()); return; }
    P("EGL %d.%d, vendor %s", major, minor, eglQueryString(dpy, EGL_VENDOR));
    const EGLint cfg_attr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8,
                               EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, cfg_attr, &cfg, 1, &n) || n < 1) { P("EGL: no ES 3 config with depth 24 / stencil 8 (0x%x)", eglGetError()); return; }
    const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pb);
    const EGLint ctx_attr[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 2, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (ctx == EGL_NO_CONTEXT) { P("EGL: an OpenGL ES 3.2 context could NOT be created (0x%x)", eglGetError()); return; }
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) { P("EGL: eglMakeCurrent FAILED (0x%x)", eglGetError()); return; }
    P("GL_VERSION  %s", reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    P("GL_RENDERER %s", reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
    P("GL_VENDOR   %s", reinterpret_cast<const char*>(glGetString(GL_VENDOR)));
    P("GLSL        %s", reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION)));
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    const char* wanted[] = {"GL_NV_shader_noperspective_interpolation", "GL_EXT_clip_control", "GL_EXT_disjoint_timer_query",
                            "GL_EXT_shader_framebuffer_fetch", "GL_EXT_texture_border_clamp", "GL_EXT_color_buffer_float"};
    for (const char* w : wanted) {
        bool have = false;
        for (GLint i = 0; i < count && !have; ++i)
        {
            const GLubyte* e = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
            have = e && std::strcmp(reinterpret_cast<const char*>(e), w) == 0;
        }
        P("  %-44s %s", w, have ? "yes" : "no");
    }
    using Src = ps2r::render::GlShaderSources;
    struct Item { const char* name; const char* (*text)(); GLenum type; };
    const Item items[] = {{"PS2 vertex", &Src::Ps2Vertex, GL_VERTEX_SHADER}, {"PS2 fragment", &Src::Ps2Fragment, GL_FRAGMENT_SHADER},
                          {"scanout vertex", &Src::ScanoutVertex, GL_VERTEX_SHADER}, {"scanout fragment", &Src::ScanoutFragment, GL_FRAGMENT_SHADER}};
    for (const Item& it : items) {
        std::string log;
        const bool desktop = Compile(it.type, it.text(), &log);
        P("engine shader '%s' as shipped (GLSL 4.10 core): %s%s%s", it.name, desktop ? "compiles" : "FAILS",
          desktop ? "" : " -- ", desktop ? "" : log.c_str());
        const bool es = Compile(it.type, AsEs(it.text(), it.type == GL_FRAGMENT_SHADER), &log);
        P("engine shader '%s' with #version 320 es: %s%s%s", it.name, es ? "compiles" : "FAILS", es ? "" : " -- ", es ? "" : log.c_str());
    }
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);
}

std::string JStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL Java_com_twinsanityevolved_game_TeDiag_nativeProbe(JNIEnv* env, jclass, jstring files_dir,
                                                                                         jstring external_dir) {
    g_probe.clear();
    const std::string files = JStr(env, files_dir), ext = JStr(env, external_dir);
    te_android_stage("probe: start (no game)");
    P("libmain.so: loaded and running (static initialisation passed), process %s, log folder %s", g_proc, g_dir);
    P("CPU: %s, AdvSIMD %s, page size %ld", "aarch64", (getauxval(AT_HWCAP) & (1 << 1)) ? "yes" : "no", ::sysconf(_SC_PAGESIZE));

    te_android_stage("probe: engine fibers (AArch64 switch)");
    g_main_fiber = ps2r::platform::FiberPosix::Current();
    g_fiber_a = ps2r::platform::FiberPosix::Create(&FiberBody, nullptr, 256 * 1024);
    if (!g_fiber_a.handle) {
        P("fibers: FiberPosix::Create FAILED");
    } else {
        for (int i = 0; i < 3; ++i) ps2r::platform::FiberPosix::Switch(g_fiber_a);
        P("fibers: 3 round trips, %s (count %d)", g_fiber_count == 3 ? "OK" : "WRONG", g_fiber_count);
        ps2r::platform::FiberPosix::Destroy(g_fiber_a);
        g_fiber_a = {};
        g_fiber_count = 0;
    }

    te_android_stage("probe: storage");
    for (const std::string& d : {files, ext}) {
        if (d.empty()) continue;
        const std::string probe = d + "/.write-test";
        const int fd = ::open(probe.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        P("folder %s: %s", d.c_str(), fd >= 0 ? "writable" : (std::string("NOT writable: ") + std::strerror(errno)).c_str());
        if (fd >= 0) { ::close(fd); ::unlink(probe.c_str()); }
    }

    te_android_stage("probe: disc image");
    std::string how, iso;
    if (!files.empty() && std::filesystem::is_regular_file(files + "/disc/twinsanity.iso")) {
        iso = files + "/disc/twinsanity.iso";
        how = "the copy made by the launcher's ISO picker";
    } else if (!ext.empty()) {
        iso = te::android::FindDiscImage(ext, &how);
    }
    if (iso.empty()) {
        P("disc image: NONE -- %s", how.c_str());
    } else {
        std::string report;
        const bool ok = te::android::CheckDiscImage(iso, &report);
        P("disc image (%s): %s%s", how.c_str(), report.c_str(), ok ? "" : "  <-- NOT USABLE");
    }

    te_android_stage("probe: OpenGL ES 3.2 (EGL pbuffer)");
    ProbeGl();

    te_android_stage("probe: done");
    te_android_finished(0);
    return env->NewStringUTF(g_probe.c_str());
}
