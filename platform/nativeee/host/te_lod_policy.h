// te_lod_policy.h -- the levels of detail's DRAW DISTANCE on the host side: the module's LodModelAt
// (platform/nativeee/wasm/graphics_models.cpp) registers its te_gfx_lod_policy block once (ps2e_gfx2_lod_policy); the host
// writes the percent the setting chose into it (100 = the game's own choice, bit for bit) and reads the block's census for the
// log: how many models with levels were drawn, at which level, how many the setting made finer or kept drawn past their
// original cut, and how far the finest meshes reached. Only the choice among the meshes the game already has loaded changes:
// the chunks' streaming, the camera's far plane, the culling, collisions and the game's logic are the game's own.
#pragma once
#include <cstdint>
#include <string>

namespace te::lod {

// The setting's percent (100, 200, 300, 400): written into the module's block now, or when it registers
void SetPercent(uint32_t percent);
uint32_t Percent();
// ps2e_gfx2_lod_policy: the module's block (a guest address), once
void Register(uint32_t policy);
// The census since the last call, per present over `presents` presents ("" before the module registered or with no lookups)
std::string CensusLine(uint64_t first, uint64_t last, double presents);

}  // namespace te::lod
