// tools/android/test_disc_chd.cpp -- the CHD image source (platform/nativeee/host/disc_image.h) under the engine's IsoFs: a
// small ISO 9660 image made here, the same image as an UNCOMPRESSED CHD v5 (a format libchdr reads like chdman's compressed
// ones: the same header, the same hunks), both opened as IsoFs volumes once the source is installed and compared -- the
// volume, a file found by name, random reads across hunk boundaries. Also: a CD-layout CHD (2448-byte units) is refused
// with its reason, and without the source installed the ISO reads as before.
//
// Built by the Android workflow against libchdr at the pinned commit (the same the APK links), with TE_HAVE_CHDR=1.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <random>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "engine/cdvd/iso_fs.h"
#include "platform/nativeee/host/disc_image.h"
#include "engine/platform/host/platform.h"

// the engine's host file calls (engine/platform/host), POSIX, without the rest of host_posix.cpp
namespace ps2r::host {
int FdOpen(const char* path, uint32_t) { return ::open(path, O_RDONLY); }
ps2r::host::SSize FdPread(int fd, void* buf, size_t n, uint64_t offset) { return ::pread(fd, buf, n, static_cast<off_t>(offset)); }
int64_t FdSize(int fd) {
    struct stat st{};
    return ::fstat(fd, &st) == 0 ? st.st_size : -1;
}
int FdClose(int fd) { return ::close(fd); }
}  // namespace ps2r::host

namespace {

int g_failures = 0;
void Check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

constexpr uint32_t kSector = 2048;

void Le32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i)); }
void Be32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; ++i) p[i] = uint8_t(v >> (24 - 8 * i)); }
void Both32(uint8_t* p, uint32_t v) { Le32(p, v); Be32(p + 4, v); }
void Be64(uint8_t* p, uint64_t v) { for (int i = 0; i < 8; ++i) p[i] = uint8_t(v >> (56 - 8 * i)); }

// One ISO 9660 directory record
size_t Record(uint8_t* p, uint32_t lba, uint32_t size, bool dir, const std::string& name) {
    const size_t len = (33 + name.size() + 1) & ~size_t(1);
    std::memset(p, 0, len);
    p[0] = uint8_t(len);
    Both32(p + 2, lba);
    Both32(p + 10, size);
    p[25] = dir ? 2 : 0;
    p[28] = 1;   // volume sequence number (both-endian 16)
    p[31] = 1;
    p[32] = uint8_t(name.size());
    std::memcpy(p + 33, name.data(), name.size());
    return len;
}

// A 64-sector image: the PVD at 16, the root directory at 18, SYSTEM.CNF at 20, the rest a pattern
std::vector<uint8_t> MakeIso(const std::string& cnf) {
    const uint32_t sectors = 64;
    std::vector<uint8_t> img(size_t(sectors) * kSector);
    std::mt19937 rng(7);
    for (auto& b : img) b = uint8_t(rng());
    uint8_t* pvd = &img[16 * kSector];
    std::memset(pvd, 0, kSector);
    pvd[0] = 1;
    std::memcpy(pvd + 1, "CD001", 5);
    pvd[6] = 1;
    std::memset(pvd + 40, ' ', 32);
    std::memcpy(pvd + 40, "TESTVOL", 7);
    Both32(pvd + 80, sectors);
    Record(pvd + 156, 18, kSector, true, std::string(1, '\0'));
    uint8_t* term = &img[17 * kSector];
    std::memset(term, 0, kSector);
    term[0] = 255;
    std::memcpy(term + 1, "CD001", 5);
    uint8_t* root = &img[18 * kSector];
    std::memset(root, 0, kSector);
    size_t at = Record(root, 18, kSector, true, std::string(1, '\0'));
    at += Record(root + at, 18, kSector, true, std::string(1, '\1'));
    Record(root + at, 20, uint32_t(cnf.size()), false, "SYSTEM.CNF;1");
    std::memset(&img[20 * kSector], 0, kSector);
    std::memcpy(&img[20 * kSector], cnf.data(), cnf.size());
    return img;
}

// The image as an uncompressed CHD v5: the 124-byte header, the map (one big-endian word a hunk: its file offset / hunk
// bytes), the hunks at hunk-aligned offsets
std::vector<uint8_t> MakeChd(const std::vector<uint8_t>& image, uint32_t hunk_bytes, uint32_t unit_bytes) {
    const uint32_t hunks = uint32_t((image.size() + hunk_bytes - 1) / hunk_bytes);
    const uint64_t map_offset = 124;
    const uint64_t first_hunk = ((map_offset + 4ull * hunks + hunk_bytes - 1) / hunk_bytes) * hunk_bytes;
    std::vector<uint8_t> out(size_t(first_hunk + uint64_t(hunks) * hunk_bytes), 0);
    std::memcpy(out.data(), "MComprHD", 8);
    Be32(&out[8], 124);
    Be32(&out[12], 5);
    Be64(&out[32], image.size());
    Be64(&out[40], map_offset);
    Be64(&out[48], 0);
    Be32(&out[56], hunk_bytes);
    Be32(&out[60], unit_bytes);
    for (uint32_t h = 0; h < hunks; ++h) {
        const uint64_t at = first_hunk + uint64_t(h) * hunk_bytes;
        Be32(&out[size_t(map_offset + 4ull * h)], uint32_t(at / hunk_bytes));
        const size_t from = size_t(h) * hunk_bytes;
        std::memcpy(&out[size_t(at)], &image[from], std::min<size_t>(hunk_bytes, image.size() - from));
    }
    return out;
}

bool WriteFile(const std::string& path, const std::vector<uint8_t>& bytes) {
    std::FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = std::fwrite(bytes.data(), 1, bytes.size(), f) == bytes.size();
    std::fclose(f);
    return ok;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string dir = argc > 1 ? argv[1] : "/tmp";
    const std::string cnf = "BOOT2 = cdrom0:\\SLUS_209.09;1\r\nVER = 2.00\r\nVMODE = NTSC\r\n";
    const std::vector<uint8_t> iso = MakeIso(cnf);
    const std::string iso_path = dir + "/te_disc_test.iso", chd_path = dir + "/te_disc_test.chd", cd_path = dir + "/te_disc_cd.chd";
    Check(WriteFile(iso_path, iso) && WriteFile(chd_path, MakeChd(iso, 4 * kSector, kSector)) &&
          WriteFile(cd_path, MakeChd(iso, 4 * 2448, 2448)), "the test images written");
    Check(te::io::ChdSupported(), "this build reads CHD (TE_HAVE_CHDR)");
    Check(!te::io::IsChdFile(iso_path) && te::io::IsChdFile(chd_path), "a CHD told apart by its first bytes");

    IsoFs before;
    std::string err;
    Check(before.Open(iso_path, &err) && !before.Open(chd_path, &err), "without the source installed: the ISO opens, the CHD is no volume");
    te::io::InstallChdSupport();
    IsoFs a, b;
    err.clear();
    const bool oa = a.Open(iso_path, &err);
    if (!oa) std::printf("      %s\n", err.c_str());
    const bool ob = b.Open(chd_path, &err);
    if (!ob) std::printf("      %s\n", err.c_str());
    Check(oa && !a.RawSectors(), "the ISO opened through the engine's own reader");
    Check(ob, "the CHD opened through libchdr");
    if (oa && ob) {
        Check(a.ImageBytes() == b.ImageBytes() && a.VolumeSectors() == b.VolumeSectors() && a.VolumeId() == b.VolumeId(),
              "the same volume (bytes, sectors, id)");
        IsoFs::Entry ea, eb;
        const bool fa = a.Find("SYSTEM.CNF", ea), fb = b.Find("cdrom0:\\SYSTEM.CNF;1", eb);
        std::vector<uint8_t> cb(fb ? eb.size : 1);   // (sized once the entry is found)
        Check(fa && fb && ea.lba == eb.lba && ea.size == eb.size && b.ReadBytes(uint64_t(eb.lba) * kSector, eb.size, cb.data()) == eb.size &&
              std::memcmp(cb.data(), cnf.data(), cnf.size()) == 0, "SYSTEM.CNF found and read from the CHD");
        std::mt19937 rng(11);
        bool same = true;
        for (int i = 0; i < 2000 && same; ++i) {
            const uint64_t at = rng() % iso.size();
            const size_t n = 1 + rng() % (3 * 4 * kSector);
            std::vector<uint8_t> ra(n), rb(n);
            const size_t ga = a.ReadBytes(at, n, ra.data()), gb = b.ReadBytes(at, n, rb.data());
            same = ga == gb && std::memcmp(ra.data(), rb.data(), ga) == 0;
        }
        Check(same, "2000 random reads (across hunks, past the end): the CHD's bytes are the ISO's");
    }
    IsoFs c;
    err.clear();
    Check(!c.Open(cd_path, &err) && err.find("createdvd") != std::string::npos, "a CD-layout CHD refused, the reason given");
    if (!std::getenv("KEEP")) std::remove(iso_path.c_str());
    if (!std::getenv("KEEP")) std::remove(chd_path.c_str());
    std::remove(cd_path.c_str());
    std::printf("%s\n", g_failures ? "DISC CHD: FAILED" : "DISC CHD: OK");
    return g_failures ? 1 : 0;
}
