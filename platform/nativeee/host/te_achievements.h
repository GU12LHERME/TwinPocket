// te_achievements.h -- LOCAL, OFFLINE achievements of the Android port (no RetroAchievements: nothing official is claimed,
// synchronised or unlocked). A first set of five, each one a place the player REACHES IN PLAY: the area the player's chunk
// moves into (the mods framework's area change, mods.cpp OnBoundary) while the game is playing, coming from another area of
// the same session -- never the first area after the title or a loaded save (a load is not a visit), and never while any
// mod is on (the level select warps). Stable ids; persisted atomically in achievements.txt beside ps2r.ini (id = Unix time
// of the unlock), apart from the saves; an unlocked one is never unlocked again. A short line at the bottom of the screen
// says so for four seconds (the platform texts: te_texts.h); the launcher's Conquistas page lists them and resets them.
#pragma once
#include <cstdint>
#include <string>

namespace te::achievements {

// The player's area changed (the mods framework's area line): `from` the area left ("" after the title / a load), `to` the
// area entered, `playing` the game state Playing / Watching. Android only (nothing on the other lanes).
void OnArea(const std::string& from, const std::string& to, bool playing, uint64_t boundary);

}  // namespace te::achievements
