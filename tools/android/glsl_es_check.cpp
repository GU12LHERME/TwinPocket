// tools/android/glsl_es_check.cpp -- writes every engine shader as the Android lane compiles it (GLSL ES 3.20 through
// engine/render/backends/gl/gl_glsl_es.cpp) to <out>/<name>.<vert|frag>, for glslangValidator in CI. The Vita-only
// sources (lean / skin / instanced) are left out: no Android draw selects them.
#include <cstdio>
#include <string>

#include "engine/render/backends/gl/gl_glsl_es.h"
#include "engine/render/backends/gl/gl_shader_sources.h"

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: glsl_es_check <out dir>\n"); return 2; }
    using S = ps2r::render::GlShaderSources;
    struct Item { const char* name; const char* (*text)(); bool fragment; };
    const Item items[] = {
        {"ps2", &S::Ps2Vertex, false}, {"ps2", &S::Ps2Fragment, true}, {"scanout", &S::ScanoutVertex, false},
        {"scanout", &S::ScanoutFragment, true}, {"scanout_dither", &S::ScanoutDitherFragment, true},
        {"stencil_clear", &S::StencilClearFragment, true}, {"stencil_strip", &S::StencilStripFragment, true},
        {"stencil_invert", &S::StencilInvertFragment, true}, {"const", &S::ConstVertex, false}, {"const", &S::ConstFragment, true},
        {"opaque_mask", &S::OpaqueMaskFragment, true}, {"copy", &S::CopyVertex, false}, {"copy", &S::CopyFragment, true},
    };
    for (const Item& it : items) {
        const std::string path = std::string(argv[1]) + "/" + it.name + (it.fragment ? ".frag" : ".vert");
        std::FILE* f = std::fopen(path.c_str(), "wb");
        if (!f) { std::perror(path.c_str()); return 1; }
        const std::string es = ps2r::render::GlslToEs(it.fragment, it.text());
        std::fwrite(es.data(), 1, es.size(), f);
        std::fclose(f);
        std::printf("%s\n", path.c_str());
    }
    return 0;
}
