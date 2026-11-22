// render/backends/gl/gl_es_diagnostics.h -- the Android lane's OpenGL ES evidence (gl_es_diagnostics.cpp): what the
// driver says about the backend's calls (KHR_debug, core in ES 3.2) and what the context offers, in the log the
// diagnostic report collects. Nothing on the other lanes.
#pragma once

namespace ps2r {
namespace render {

// With the backend's context current: the context's summary line, then the driver's debug messages (errors, undefined
// behaviour, performance warnings) logged as "[gl.debug]" lines -- each distinct message the first time and its count
// at intervals. A no-op except on PS2R_HOST_ANDROID.
void InstallEsDiagnostics();

// The one-picture draw capture (the port's diagnosis: platform/nativeee/host graphics.cpp, L3 + R3 held): while on, the
// executor logs each textured draw's sampler as chosen and as the driver holds it ("[gl.capture]" lines). Every lane.
void SetDrawCapture(bool on);
bool DrawCapture();

}  // namespace render
}  // namespace ps2r
