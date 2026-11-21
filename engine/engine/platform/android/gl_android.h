// engine/platform/android/gl_android.h -- the GL header of the Android target (PS2R_GL_HEADER in
// engine/platform/host/platform.h). The GL backend (engine/render/backends/gl) is written against GL 4.1 core; on
// Android the context is OpenGL ES 3.2, whose API is that core profile minus a handful of desktop-only entry points and
// enums. This header includes the ES 3.2 prototypes (libGLESv3 exports every one of them) and maps the few desktop
// spellings the backend uses onto their ES equivalents, so the backend's sources compile unchanged:
//
//   glClearDepth(double)      -> glClearDepthf(float)            (ES has only the float form)
//   glDrawBuffer(GL_NONE)     -> glDrawBuffers(1, &GL_NONE)      (ES has only the array form)
//   GL_TIME_ELAPSED           -> GL_TIME_ELAPSED_EXT             (EXT_disjoint_timer_query; the GPU timers are a
//                                                                 diagnostic and stay off where the query is refused)
//
// Shading-language differences (#version 410 core -> #version 320 es) are a run-time matter of the shader library,
// not of this header (Android plan, stage 3). PS2R_GLES_COMPAT_CHECK lets a desktop Linux build compile the backend
// against this header with the system's Khronos ES headers, as a host-side check of this mapping.
#pragma once

#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>

#ifndef GL_TIME_ELAPSED
#ifdef GL_TIME_ELAPSED_EXT
#define GL_TIME_ELAPSED GL_TIME_ELAPSED_EXT
#else
#define GL_TIME_ELAPSED 0x88BF
#endif
#endif

#ifndef GL_QUERY_RESULT_AVAILABLE
#define GL_QUERY_RESULT_AVAILABLE 0x8867
#endif

// GL_SAMPLES_PASSED (a sample count) has only the boolean ES form; the backend reads it for its GPU-phase diagnostic only.
#ifndef GL_SAMPLES_PASSED
#define GL_SAMPLES_PASSED GL_ANY_SAMPLES_PASSED
#endif

#include <EGL/egl.h>

#ifdef __cplusplus
extern "C++" {
inline void ps2r_gles_glClearDepth(double depth) { glClearDepthf(static_cast<GLfloat>(depth)); }
inline void ps2r_gles_glDrawBuffer(GLenum buffer) { glDrawBuffers(1, &buffer); }
// glGetQueryObjectiv: ES has the unsigned query only (the results read here are availability flags and counts).
inline void ps2r_gles_glGetQueryObjectiv(GLuint id, GLenum pname, GLint* params) {
    GLuint v = 0;
    glGetQueryObjectuiv(id, pname, &v);
    if (params) *params = static_cast<GLint>(v);
}
// glGetQueryObjectui64v: EXT_disjoint_timer_query's entry point, looked up once through EGL (an extension function is
// not a libGLESv3 export). Without the extension the result is 0: the GPU timers are a diagnostic.
inline void ps2r_gles_glGetQueryObjectui64v(GLuint id, GLenum pname, GLuint64* params) {
    using Fn = void (*)(GLuint, GLenum, GLuint64*);
    static Fn fn = reinterpret_cast<Fn>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
    if (fn) {
        fn(id, pname, params);
    } else if (params) {
        *params = 0;
    }
}
}  // extern "C++"
#define glClearDepth ps2r_gles_glClearDepth
#define glDrawBuffer ps2r_gles_glDrawBuffer
#define glGetQueryObjectiv ps2r_gles_glGetQueryObjectiv
#define glGetQueryObjectui64v ps2r_gles_glGetQueryObjectui64v
#endif  // __cplusplus
