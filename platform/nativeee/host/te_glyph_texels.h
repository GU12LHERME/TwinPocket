// te_glyph_texels.h -- a glyph quad as the host draws it (graphics.cpp ps2e_gfx2_text_quads).
//
// The glyphs of a font page sit edge to edge in its texture and the page repeats. Under a FILTERED sampler (the player's
// bilinear / trilinear, or a material that magnifies linear) a quad drawn above the PS2's resolution samples into the
// neighbouring glyph's columns at its edges: thin vertical lines beside the letters of the menus (Android, Retroid Pocket
// 6, bilinear at 3x). 0.19.0 pulled the texture rectangle in by half a texel (the lines fainter, not gone: the cells are
// packed tighter than that) and stretched the glyph to fill the quad.
//
// Now, filtered, each glyph is CROPPED: kCropU texels off its left and right, kCropV off its top and bottom -- the texture
// rectangle and the screen quad together, at the glyph's own scale, so nothing is stretched or moved: the letter is drawn
// exactly where and as large as before, less the outermost texel columns (the cells' padding). Unfiltered (title / point)
// the glyph is the game's own, untouched. A glyph too narrow to crop keeps its rectangle.
#pragma once

namespace te::text {

constexpr float kCropU = 1.0f;   // texels off each side (horizontal: the lines were vertical)
constexpr float kCropV = 0.5f;   // texels off the top and the bottom

// In: the quad's screen corners (any unit) and its texture rectangle in 16ths of a texel; tw/th the page's size in
// texels. Out: the corners (same unit) and texture coordinates.
inline void GlyphQuad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, bool filtered,
                      float tw, float th, float* ox0, float* oy0, float* ox1, float* oy1, float* ou0, float* ov0,
                      float* ou1, float* ov1) {
    if (filtered) {
        // one axis: crop c texels (c * 16 units) off both ends of [a, b] and move the screen ends [p, q] by the same share
        auto crop = [](float& a, float& b, float& p, float& q, float c) {
            const float span = b - a;                    // 16ths of a texel, either sign
            const float mag = span < 0 ? -span : span;
            const float cut = c * 16.f;
            if (mag <= 2.f * cut + 16.f) return;         // too narrow: kept
            const float share = cut / mag;               // the part of the glyph cut off at each end
            const float dp = (q - p) * share;
            const float da = span * share;
            a += da;
            b -= da;
            p += dp;
            q -= dp;
        };
        crop(u0, u1, x0, x1, kCropU);
        crop(v0, v1, y0, y1, kCropV);
    }
    *ox0 = x0;
    *oy0 = y0;
    *ox1 = x1;
    *oy1 = y1;
    *ou0 = u0 / 16.f / tw;
    *ou1 = u1 / 16.f / tw;
    *ov0 = v0 / 16.f / th;
    *ov1 = v1 / 16.f / th;
}

}  // namespace te::text
