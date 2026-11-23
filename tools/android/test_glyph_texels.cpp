// The glyph quads under a filtered sampler (te_glyph_texels.h): unfiltered untouched; filtered, cropped by kCropU / kCropV
// texels in the texture AND on screen at the glyph's own scale (no stretch, no move); a glyph too narrow is kept.
#include "te_glyph_texels.h"
#include <cmath>
#include <cstdio>

static int failures = 0;
static void Expect(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) failures++;
}
static bool Near(float a, float b) { return std::fabs(a - b) < 1e-4f; }

int main() {
    using namespace te::text;
    float x0, y0, x1, y1, u0, v0, u1, v1;
    // a 20 x 24 texel glyph at texel (40, 100), drawn 60 x 72 pixels at (300, 200): 3 pixels a texel; v runs down
    GlyphQuad(300, 200, 360, 272, 40 * 16, 100 * 16, 60 * 16, 76 * 16, false, 256, 256, &x0, &y0, &x1, &y1, &u0, &v0, &u1, &v1);
    Expect(Near(x0, 300) && Near(x1, 360) && Near(u0, 40 / 256.f) && Near(u1, 60 / 256.f) && Near(v1, 76 / 256.f), "unfiltered untouched");
    GlyphQuad(300, 200, 360, 272, 40 * 16, 100 * 16, 60 * 16, 76 * 16, true, 256, 256, &x0, &y0, &x1, &y1, &u0, &v0, &u1, &v1);
    Expect(Near(u0, (40 + kCropU) / 256.f) && Near(u1, (60 - kCropU) / 256.f), "filtered: kCropU texels off each side");
    Expect(Near(x0, 300 + 3 * kCropU) && Near(x1, 360 - 3 * kCropU), "the screen edges cut at the glyph's own scale (3 px a texel)");
    const float scaleBefore = 60.f / 20.f, scaleAfter = (x1 - x0) / ((u1 - u0) * 256.f);
    Expect(Near(scaleBefore, scaleAfter), "no stretch: pixels per texel unchanged");
    Expect(Near(v0, (100 - kCropV) / 256.f) && Near(v1, (76 + kCropV) / 256.f) && Near(y0, 200 + 3 * kCropV) && Near(y1, 272 - 3 * kCropV),
           "vertical crop along the downward v, the screen likewise");
    GlyphQuad(10, 0, 13, 3, 10 * 16, 0, 11 * 16, 0, true, 256, 256, &x0, &y0, &x1, &y1, &u0, &v0, &u1, &v1);
    Expect(Near(x0, 10) && Near(x1, 13) && Near(u0, 10 / 256.f) && Near(u1, 11 / 256.f), "a 1-texel glyph kept");
    std::printf("test_glyph_texels: %s\n", failures ? "FAILED" : "ok");
    return failures ? 1 : 0;
}
