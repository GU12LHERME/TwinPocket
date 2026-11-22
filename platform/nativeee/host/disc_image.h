// disc_image.h -- the CHD disc image (`chdman createdvd`: the PS2 DVD layout, 2048-byte units) as an IsoFs image source
// (engine/cdvd/iso_fs.h SetImageSourceFactory), where the build has libchdr (TE_HAVE_CHDR: the Android lane). Installed
// once at start-up: from then every volume the port opens -- the game's disc, the region overlay's base, the start-up
// checks -- reads a CHD's sectors through libchdr, and an ISO / BIN exactly as before. Which one is decided by the file's
// first bytes ("MComprHD"), never by its name.
//
// A CHD is read a hunk at a time (libchdr decompresses it: zlib / LZMA / zstd / FLAC as chdman chose), a few decoded hunks
// kept (LRU) under one lock (the disc reader thread and the game thread both read). Refused, with the reason: a CD CHD
// (`chdman createcd`: 2448-byte frames -- the PS2 DVD games are DVDs) and a CHD with a parent.
#pragma once
#include <string>

namespace te::io {

// Whether the file at `path` is a CHD (its first 8 bytes "MComprHD")
bool IsChdFile(const std::string& path);
// Whether this build reads CHD images (TE_HAVE_CHDR)
bool ChdSupported();
// IsoFs::SetImageSourceFactory with the CHD reader (nothing without libchdr); once, before any volume opens
void InstallChdSupport();

}  // namespace te::io
