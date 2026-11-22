// platform/nativeee (host side) -- te_lod_policy.h: the draw distance's policy and census. See the header.
#include "te_lod_policy.h"

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "ps2e_seam.h"
#include "te_gfx_layout.h"
#include "te_host.h"

namespace te::lod {

namespace {
std::atomic<uint32_t> g_block{0};       // the module's te_gfx_lod_policy (a guest address), 0 until it registers
std::atomic<uint32_t> g_percent{100};
te_gfx_lod_policy g_last{};             // the counters at the last census

te_gfx_lod_policy* Block() {
    const uint32_t at = g_block.load(std::memory_order_acquire);
    if (!at) return nullptr;
    auto* p = reinterpret_cast<te_gfx_lod_policy*>(te::GuestPointer(at, sizeof(te_gfx_lod_policy)));
    return p && p->version == TE_GFX_LOD_POLICY_VERSION ? p : nullptr;
}

double Units(uint32_t squared) { return std::sqrt(double(squared)); }
}  // namespace

void SetPercent(uint32_t percent) {
    if (percent < 100) percent = 100;
    g_percent.store(percent, std::memory_order_relaxed);
    if (te_gfx_lod_policy* p = Block()) p->percent = percent;
}

uint32_t Percent() { return g_percent.load(std::memory_order_relaxed); }

void Register(uint32_t policy) {
    auto* p = reinterpret_cast<te_gfx_lod_policy*>(te::GuestPointer(policy, sizeof(te_gfx_lod_policy)));
    if (!p || p->version != TE_GFX_LOD_POLICY_VERSION) {
        std::fprintf(stderr, "[te.gfx] lod: the module's policy block at 0x%x is not version %u: the game's own choice\n", policy,
                     TE_GFX_LOD_POLICY_VERSION);
        return;
    }
    p->percent = Percent();
    std::memcpy(&g_last, p, sizeof g_last);
    g_block.store(policy, std::memory_order_release);
    std::fprintf(stderr, "[te.gfx] lod: the module's levels of detail registered (block 0x%x): draw distance %u %%\n", policy, p->percent);
}

std::string CensusLine(uint64_t first, uint64_t last, double presents) {
    te_gfx_lod_policy* p = Block();
    if (!p || presents <= 0) return "";
    te_gfx_lod_policy now;
    std::memcpy(&now, p, sizeof now);
    p->reach_finest = 0;   // the reaches are per window (a race with the game thread costs one sample)
    p->reach_drawn = 0;
    const auto d = [&](uint32_t te_gfx_lod_policy::*field) { return double(now.*field - g_last.*field) / presents; };
    double chosen[4];
    for (int i = 0; i < 4; ++i) chosen[i] = double(now.chosen[i] - g_last.chosen[i]) / presents;
    const double lookups = d(&te_gfx_lod_policy::lookups);
    g_last = now;
    if (lookups <= 0) return "";
    char buf[700];
    std::snprintf(buf, sizeof buf,
                  "[te.gfx] lod: presents %llu-%llu at draw distance %u %%, per present: %.0f models with levels (%.0f with 2+ levels, "
                  "%.0f far stand-ins); drawn at level 0 (finest) %.0f, 1 %.0f, 2 %.0f, 3 %.0f; none past the farthest %.0f, nearer "
                  "than the nearest %.0f; the setting's doing: %.0f finer than the game's choice, %.0f drawn past its cut, %.0f "
                  "stand-ins hidden; finest drawn up to %.1f units, any level up to %.1f units\n",
                  (unsigned long long)first, (unsigned long long)last, now.percent, lookups, d(&te_gfx_lod_policy::multi_level),
                  d(&te_gfx_lod_policy::near_limited), chosen[0], chosen[1], chosen[2], chosen[3], d(&te_gfx_lod_policy::cut_far),
                  d(&te_gfx_lod_policy::cut_near), d(&te_gfx_lod_policy::finer), d(&te_gfx_lod_policy::drawn_past_cut),
                  d(&te_gfx_lod_policy::hidden_near), Units(now.reach_finest), Units(now.reach_drawn));
    return buf;
}

}  // namespace te::lod

extern "C" void ps2e_gfx2_lod_policy(ps2e_u32 policy) { te::lod::Register(policy); }
