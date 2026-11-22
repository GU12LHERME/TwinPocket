// te_glyph_texels.h -- a glyph quad's texel rectangle as the host samples it (graphics.cpp ps2e_gfx2_text_quads).
//
// The glyphs of a font page sit edge to edge in its texture and the page repeats. Under a FILTERED sampler (the player's
// bilinear / trilinear, or a material that magnifies linear) a quad drawn above the PS2's resolution has its edge pixels'
// centres between texels, and the filter mixes in the neighbouring glyph's column (or the page's opposite edge): the thin
// vertical lines in the spaces between the words of the menus (Android, Retroid Pocket 6, bilinear at 3x). So, filtered, the
// rectangle is pulled in by half a texel on each side (never past its middle); unfiltered it is the game's own, untouched.
#pragma once

namespace te::text {

// u0/v0/u1/v1 in 16ths of a texel (the glyph's quadword units); tw/th the page's size in texels; out: texture coordinates
inline void GlyphTexels(float u0, float v0, float u1, float v1, bool filtered, float tw, float th, float* ou0, float* ov0,
                        float* ou1, float* ov1) {
    if (filtered) {
        constexpr float kHalf = 8.f;   // half a texel
        auto inset = [](float& lo, float& hi) {
            const float sign = hi >= lo ? 1.f : -1.f;
            if ((hi - lo) * sign > 2.f * kHalf) {
                lo += kHalf * sign;
                hi -= kHalf * sign;
            }
        };
        inset(u0, u1);
        inset(v0, v1);   // (the game's v runs downwards from y: v1 = y - height)
    }
    *ou0 = u0 / 16.f / tw;
    *ou1 = u1 / 16.f / tw;
    *ov0 = v0 / 16.f / th;
    *ov1 = v1 / 16.f / th;
}

}  // namespace te::text
