// te_lod_choice.h -- the level of detail's choice, shared by the module (platform/nativeee/wasm/graphics_models.cpp
// LodModelAt) and its host test (tools/android/test_lod_choice.cpp): the walk of the PS2 renderer's LodMeshAt
// (src/platform/ps2/renderer/resources.cpp) over a te_gfx_lod, and the draw distance's distance.
#pragma once
#include <stdint.h>

#include "te_gfx_layout.h"

// The level drawn at a squared distance: -1 none (nearer than its nearest, past its farthest), else the mesh's index
static inline int32_t te_lod_level_at(const te_gfx_lod* lod, uint32_t distance) {
    if (distance < lod->nearest || lod->farthest < distance) return -1;
    const int32_t last = (int32_t)lod->count - 1;
    if (last <= 0 || distance < lod->switchDistances[0]) return 0;
    int32_t level = 1;
    for (int32_t index = 1; index < last && distance >= lod->switchDistances[index]; index++) level = index + 1;
    return level;
}

// The squared distance a level is chosen by at the draw distance's percent: the distance itself at 100 or less (the game's
// own choice); above, as if the model were percent / 100 times nearer (divided by the square)
static inline uint32_t te_lod_distance(uint32_t distance, uint32_t percent) {
    if (percent <= 100) return distance;
    return (uint32_t)((uint64_t)distance * 10000u / ((uint64_t)percent * percent));
}
