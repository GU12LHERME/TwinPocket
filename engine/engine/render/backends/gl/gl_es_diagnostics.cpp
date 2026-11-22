// render/backends/gl/gl_es_diagnostics.cpp -- see gl_es_diagnostics.h.
#include "engine/render/backends/gl/gl_es_diagnostics.h"

#include "engine/platform/host/platform.h"
#include PS2R_GL_HEADER

#if PS2R_HOST_ANDROID
#include <EGL/egl.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <unordered_map>
#endif

#include <atomic>

namespace ps2r {
namespace render {

namespace {
std::atomic<bool> g_draw_capture{false};
}  // namespace
void SetDrawCapture(bool on) { g_draw_capture.store(on, std::memory_order_relaxed); }
bool DrawCapture() { return g_draw_capture.load(std::memory_order_relaxed); }

#if PS2R_HOST_ANDROID
namespace {

std::mutex g_mutex;
std::unordered_map<GLuint, uint64_t> g_seen;   // message id -> count
std::atomic<uint64_t> g_total{0};

const char* SourceName(GLenum s) {
    switch (s) {
        case GL_DEBUG_SOURCE_API: return "api";
        case GL_DEBUG_SOURCE_SHADER_COMPILER: return "shader";
        case GL_DEBUG_SOURCE_WINDOW_SYSTEM: return "window";
        case GL_DEBUG_SOURCE_THIRD_PARTY: return "third-party";
        case GL_DEBUG_SOURCE_APPLICATION: return "app";
        default: return "other";
    }
}
const char* TypeName(GLenum t) {
    switch (t) {
        case GL_DEBUG_TYPE_ERROR: return "ERROR";
        case GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR: return "deprecated";
        case GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR: return "UNDEFINED";
        case GL_DEBUG_TYPE_PORTABILITY: return "portability";
        case GL_DEBUG_TYPE_PERFORMANCE: return "performance";
        case GL_DEBUG_TYPE_MARKER: return "marker";
        default: return "other";
    }
}
const char* SeverityName(GLenum s) {
    switch (s) {
        case GL_DEBUG_SEVERITY_HIGH: return "high";
        case GL_DEBUG_SEVERITY_MEDIUM: return "medium";
        case GL_DEBUG_SEVERITY_LOW: return "low";
        default: return "note";
    }
}

void GL_APIENTRY OnDebugMessage(GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar* message,
                                const void*) {
    if (severity == GL_DEBUG_SEVERITY_NOTIFICATION && type != GL_DEBUG_TYPE_ERROR) return;
    uint64_t n;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        n = ++g_seen[id];
    }
    const uint64_t total = ++g_total;
    // each distinct message once, then its count at 10, 100, 1000, ...; at most 400 lines in all
    if (total > 400 && n != 1) return;
    if (n == 1 || n == 10 || n == 100 || n == 1000 || n == 10000 || n == 100000) {
        const int len = length > 0 ? static_cast<int>(length) : static_cast<int>(std::strlen(message ? message : ""));
        std::fprintf(stderr, "[gl.debug] %s %s %s id 0x%x (seen %llu): %.*s\n", SeverityName(severity), TypeName(type),
                     SourceName(source), id, static_cast<unsigned long long>(n), len > 600 ? 600 : len, message ? message : "");
    }
}

bool HasExtension(const char* name) {
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; ++i) {
        const GLubyte* e = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
        if (e && std::strcmp(reinterpret_cast<const char*>(e), name) == 0) return true;
    }
    return false;
}

}  // namespace

void InstallEsDiagnostics() {
    const GLubyte* r = glGetString(GL_RENDERER);
    const GLubyte* v = glGetString(GL_VERSION);
    const GLubyte* sl = glGetString(GL_SHADING_LANGUAGE_VERSION);
    GLint range[2] = {0, 0}, precision = 0, ints[2] = {0, 0}, iprec = 0;
    glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, range, &precision);
    glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER, GL_HIGH_INT, ints, &iprec);
    GLint max_tex = 0, max_aniso_units = 0, samples = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_tex);
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &max_aniso_units);
    glGetIntegerv(GL_MAX_SAMPLES, &samples);
    std::fprintf(stderr, "[gl.es] %s | %s | %s\n", r ? reinterpret_cast<const char*>(r) : "?", v ? reinterpret_cast<const char*>(v) : "?",
                 sl ? reinterpret_cast<const char*>(sl) : "?");
    std::fprintf(stderr, "[gl.es] fragment highp float: range 2^%d..2^%d, %d bits of precision; highp int: range 2^%d; max texture %d, "
                         "texture units %d, max samples %d\n",
                 -range[0], range[1], precision, ints[1], max_tex, max_aniso_units, samples);
    const char* exts[] = {"GL_NV_shader_noperspective_interpolation", "GL_EXT_texture_filter_anisotropic", "GL_EXT_clip_control",
                          "GL_EXT_disjoint_timer_query", "GL_EXT_shader_framebuffer_fetch", "GL_EXT_color_buffer_float",
                          "GL_EXT_texture_border_clamp", "GL_KHR_debug", "GL_NV_read_depth", "GL_EXT_depth_clamp"};
    for (const char* e : exts) std::fprintf(stderr, "[gl.es]   %-44s %s\n", e, HasExtension(e) ? "yes" : "no");

    // KHR_debug is core in ES 3.2; the entry points are looked up through EGL (a driver may export the KHR names only)
    using Callback = void (*)(GLenum, GLenum, GLuint, GLenum, GLsizei, const GLchar*, const void*);
    using DebugCallbackFn = void (*)(Callback, const void*);
    using DebugControlFn = void (*)(GLenum, GLenum, GLenum, GLsizei, const GLuint*, GLboolean);
    auto cb = reinterpret_cast<DebugCallbackFn>(eglGetProcAddress("glDebugMessageCallback"));
    if (!cb) cb = reinterpret_cast<DebugCallbackFn>(eglGetProcAddress("glDebugMessageCallbackKHR"));
    auto ctl = reinterpret_cast<DebugControlFn>(eglGetProcAddress("glDebugMessageControl"));
    if (!ctl) ctl = reinterpret_cast<DebugControlFn>(eglGetProcAddress("glDebugMessageControlKHR"));
    if (!cb) {
        std::fprintf(stderr, "[gl.es] driver debug messages: not available (no glDebugMessageCallback)\n");
        return;
    }
    glEnable(GL_DEBUG_OUTPUT);
    if (ctl) ctl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, nullptr, GL_TRUE);
    cb(&OnDebugMessage, nullptr);
    std::fprintf(stderr, "[gl.es] driver debug messages: on (\"[gl.debug]\" lines: errors, undefined behaviour, performance)\n");
    // anything already pending from the context's set-up
    for (int i = 0; i < 16; ++i) {
        const GLenum e = glGetError();
        if (e == GL_NO_ERROR) break;
        std::fprintf(stderr, "[gl.es] pending GL error 0x%04x before the debug output\n", e);
    }
}
#else
void InstallEsDiagnostics() {}
#endif

}  // namespace render
}  // namespace ps2r
