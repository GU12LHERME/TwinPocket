// render/backends/gl/gl_glsl_es.h -- the GLSL 4.10 core sources as GLSL ES 3.20 (the Android lane's OpenGL ES 3.2
// context). GlShaderLibrary::CompileShader applies it on PS2R_HOST_ANDROID; tools/android/glsl_es_check.cpp validates
// every engine shader through it with glslang in CI.
#pragma once

#include <cstdint>
#include <string>

namespace ps2r {
namespace render {

// `fragment`: the stage (the precision defaults differ only in what a fragment shader must declare).
std::string GlslToEs(bool fragment, const char* source);

}  // namespace render
}  // namespace ps2r
