// The glyph quads' half-texel inset under a filtered sampler (te_glyph_texels.h): unfiltered untouched, filtered pulled in by
// half a texel on each side whichever way the rectangle runs, never inverted for a rectangle a texel wide or less.
#include "te_glyph_texels.h"
#include <cmath>
#include <cstdio>

static int failures = 0;
static void Expect(bool ok, const char* what) {
    if (!ok) { std::fprintf(stderr, "FAIL %s\n", what); failures++; }
}
static bool Near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

int main() {
    float u0, v0, u1, v1;
    // a 20x24 texel glyph at texel (40, 100) of a 256x256 page; v runs down (v1 = v0 - height)
    te::text::GlyphTexels(40 * 16, 100 * 16, 60 * 16, 76 * 16, false, 256, 256, &u0, &v0, &u1, &v1);
    Expect(Near(u0, 40 / 256.f) && Near(u1, 60 / 256.f) && Near(v0, 100 / 256.f) && Near(v1, 76 / 256.f), "unfiltered untouched");
    te::text::GlyphTexels(40 * 16, 100 * 16, 60 * 16, 76 * 16, true, 256, 256, &u0, &v0, &u1, &v1);
    Expect(Near(u0, 40.5f / 256.f) && Near(u1, 59.5f / 256.f), "filtered u inset half a texel");
    Expect(Near(v0, 99.5f / 256.f) && Near(v1, 76.5f / 256.f), "filtered v inset half a texel (downward v)");
    // a rectangle a texel wide (or empty) is left alone: never inverted
    te::text::GlyphTexels(10 * 16, 0, 11 * 16, 0, true, 256, 256, &u0, &v0, &u1, &v1);
    Expect(Near(u0, 10 / 256.f) && Near(u1, 11 / 256.f) && Near(v0, 0) && Near(v1, 0), "narrow kept");
    // reversed u (a mirrored glyph) insets towards its middle too
    te::text::GlyphTexels(60 * 16, 0, 40 * 16, 0, true, 256, 256, &u0, &v0, &u1, &v1);
    Expect(Near(u0, 59.5f / 256.f) && Near(u1, 40.5f / 256.f), "reversed u inset");
    std::printf("test_glyph_texels: %s\n", failures ? "FAILED" : "ok");
    return failures ? 1 : 0;
}
