// tools/android/test_lod_choice.cpp -- the level of detail's choice of the module (platform/nativeee/te_lod_choice.h, used by
// platform/nativeee/wasm/graphics_models.cpp LodModelAt) against the walk the port had before the draw distance (the PS2
// renderer's LodMeshAt), over random levels of detail and distances:
//   - at the draw distance's 100 % (the default: original) the choice is the game's own, for every distance;
//   - at 200 - 800 % a model drawn by the game's choice is drawn at the same or a finer level -- unless it is a far
//     stand-in (nearest > 0) the closer distance now hides -- and the finest level reaches percent / 100 times as far;
//   - the largest distances do not overflow.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>

#include "platform/nativeee/te_lod_choice.h"

namespace {

int g_failures = 0;
void Check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

// The walk before the draw distance (graphics_models.cpp LodModelAt as of 0.6.0), as a level index
int32_t Reference(const te_gfx_lod* lod, uint32_t distance) {
    if (lod->count == 0 || distance < lod->nearest || lod->farthest < distance) return -1;
    int32_t last = lod->count - 1;
    if (last <= 0 || distance < lod->switchDistances[0]) return 0;
    int32_t mesh = 1;
    for (int32_t index = 1; index < last && distance >= lod->switchDistances[index]; index++) mesh = index + 1;
    return mesh;
}

te_gfx_lod RandomLod(std::mt19937& rng) {
    te_gfx_lod lod{};
    lod.count = uint8_t(1 + rng() % 4);
    auto sq = [](uint32_t v) { return v * v; };
    uint32_t at = rng() % 40;
    for (int i = 0; i < TE_GFX_LOD_SWITCHES; ++i) {
        at += 5 + rng() % 60;
        lod.switchDistances[i] = sq(at);
    }
    lod.nearest = rng() % 4 == 0 ? sq(rng() % 50) : 0;
    lod.farthest = rng() % 3 == 0 ? 0xFFFFFFFFu : sq(at + 10 + rng() % 200);
    return lod;
}

}  // namespace

int main() {
    std::mt19937 rng(20261122);
    long same = 0, total = 0, coarser = 0, unhidden = 0;
    for (int n = 0; n < 20000; ++n) {
        const te_gfx_lod lod = RandomLod(rng);
        for (int k = 0; k < 64; ++k) {
            const uint32_t distance = k == 0 ? 0xFFFFFFFFu : uint32_t(rng() % 400000u);
            ++total;
            const int32_t original = te_lod_level_at(&lod, distance);
            same += original == Reference(&lod, distance) && te_lod_distance(distance, 100) == distance;
            for (uint32_t percent : {200u, 300u, 400u, 600u, 800u}) {
                const int32_t level = te_lod_level_at(&lod, te_lod_distance(distance, percent));
                if (original >= 0 && level > original) ++coarser;
                if (original >= 0 && level < 0 && lod.nearest == 0) ++unhidden;
            }
        }
    }
    char what[160];
    std::snprintf(what, sizeof what, "original (100 %%): the game's own choice for all %ld distances", total);
    Check(same == total, what);
    Check(coarser == 0, "200 - 800 %: never a coarser level than the game's choice");
    Check(unhidden == 0, "200 - 800 %: a model the game draws is never hidden (but a far stand-in, nearest > 0)");

    // the finest level's reach: a model whose first switch is at 30 units keeps its finest mesh to 30 x percent / 100
    te_gfx_lod lod{};
    lod.count = 3;
    lod.switchDistances[0] = 30 * 30;
    lod.switchDistances[1] = 80 * 80;
    lod.switchDistances[2] = 0xFFFFFFFFu;
    lod.farthest = 120 * 120;
    for (uint32_t percent : {100u, 200u, 300u, 400u, 600u, 800u}) {
        uint32_t reach = 0;
        for (uint32_t units = 1; units < 1000; ++units)
            if (te_lod_level_at(&lod, te_lod_distance(units * units, percent)) == 0) reach = units;
        const uint32_t want = 30 * percent / 100;
        std::snprintf(what, sizeof what, "%u %%: the finest mesh reaches %u units (want %u: the first switch at 30 units)", percent,
                      reach, want - 1);
        Check(reach + 1 == want, what);
    }
    Check(te_lod_distance(0xFFFFFFFFu, 400) == 0xFFFFFFFFu / 16u, "the largest squared distance at 400 %: no overflow");
    Check(te_lod_distance(0xFFFFFFFFu, 800) == 0xFFFFFFFFu / 64u, "the largest squared distance at 800 %: no overflow");
    std::printf("%s\n", g_failures ? "LOD CHOICE: FAILED" : "LOD CHOICE: OK");
    return g_failures ? 1 : 0;
}
