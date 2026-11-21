// render/backends/gl/gl_glsl_es.cpp -- see gl_glsl_es.h.
//
// What changes between the desktop text and GLSL ES 3.20:
//   - `#version 410 core` -> `#version 320 es`, then the default precisions (highp: the PS2's 32-bit integer and float
//     arithmetic -- Z, colour masks, texel addresses -- needs every bit; ES has no default float precision in a fragment
//     shader and none for the integer samplers anywhere).
//   - `noperspective`: core in desktop GLSL, an extension in ES (GL_NV_shader_noperspective_interpolation). Enabled where
//     the driver has it; elsewhere the qualifier is dropped -- the PS2 draws' positions carry w = 1 (screen space), so
//     perspective-correct interpolation equals screen-linear there (the Vita's GLSL translation drops it the same way).
//   - Anything else the ES compiler refuses shows in the engine's "[gl] shader compile error" log line and in CI's
//     glslang validation of every source (tools/android/glsl_es_check.cpp).
#include "engine/render/backends/gl/gl_glsl_es.h"

#include <regex>

namespace ps2r {
namespace render {

std::string GlslToEs(bool fragment, const char* source) {
    std::string s = source ? source : "";
    s = std::regex_replace(s, std::regex(R"(\bnoperspective\b)"), "TE_NOPERSPECTIVE");
    std::string header =
        "#version 320 es\n"
        "#extension GL_NV_shader_noperspective_interpolation : enable\n"
        "#ifdef GL_NV_shader_noperspective_interpolation\n"
        "#define TE_NOPERSPECTIVE noperspective\n"
        "#else\n"
        "#define TE_NOPERSPECTIVE\n"
        "#endif\n"
        "precision highp float;\n"
        "precision highp int;\n"
        "precision highp sampler2D;\n"
        "precision highp usampler2D;\n"
        "precision highp isampler2D;\n";
    if (fragment) header += "precision highp sampler2DShadow;\n";
    std::smatch m;
    if (std::regex_search(s, m, std::regex(R"(#version[ \t]+\d+[ \t]*\w*[ \t]*\r?\n?)"))) {
        // ES wants #version as the very first line: what stood before it (the sources' leading newline) goes
        s = header + s.substr(static_cast<size_t>(m.position(0) + m.length(0)));
    } else {
        s = header + s;
    }
    return s;
}

}  // namespace render
}  // namespace ps2r
