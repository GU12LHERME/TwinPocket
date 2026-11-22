// platform/nativeee (host side) -- disc_image.h: a DVD CHD as an IsoFs image source (TE_HAVE_CHDR). See the header.
#include "disc_image.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <list>
#include <memory>
#include <mutex>
#include <vector>

#include "engine/cdvd/iso_fs.h"

#if TE_HAVE_CHDR
#include <libchdr/chd.h>
#endif

namespace te::io {

bool IsChdFile(const std::string& path) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    char magic[8] = {};
    const bool chd = std::fread(magic, 1, 8, f) == 8 && std::memcmp(magic, "MComprHD", 8) == 0;
    std::fclose(f);
    return chd;
}

bool ChdSupported() {
#if TE_HAVE_CHDR
    return true;
#else
    return false;
#endif
}

#if TE_HAVE_CHDR
namespace {

class ChdSource : public IsoFs::ImageSource {
public:
    ChdSource(chd_file* file, const chd_header& h, std::string path)
        : file_(file), hunk_bytes_(h.hunkbytes), logical_(h.logicalbytes), path_(std::move(path)) {}
    ~ChdSource() override {
        std::fprintf(stderr, "[te.io] disc image: CHD %s closed (%llu hunks decoded, %llu reads from the kept hunks)\n", path_.c_str(),
                     (unsigned long long)decoded_, (unsigned long long)hits_);
        chd_close(file_);
    }
    uint64_t Size() const override { return logical_; }
    size_t Read(uint64_t offset, size_t size, uint8_t* dst) const override {
        if (size == 0 || offset >= logical_) return 0;
        const size_t n = static_cast<size_t>(std::min<uint64_t>(size, logical_ - offset));
        std::lock_guard<std::mutex> lock(lock_);
        size_t done = 0;
        while (done < n) {
            const uint64_t at = offset + done;
            const std::vector<uint8_t>* hunk = Hunk(static_cast<uint32_t>(at / hunk_bytes_));
            if (!hunk) break;
            const size_t in_hunk = static_cast<size_t>(at % hunk_bytes_);
            const size_t take = std::min(n - done, static_cast<size_t>(hunk_bytes_) - in_hunk);
            std::memcpy(dst + done, hunk->data() + in_hunk, take);
            done += take;
        }
        return done;
    }

private:
    // a decoded hunk, kept (most recent first, kKeep of them)
    const std::vector<uint8_t>* Hunk(uint32_t number) const {
        for (auto it = cache_.begin(); it != cache_.end(); ++it) {
            if (it->first == number) {
                if (it != cache_.begin()) cache_.splice(cache_.begin(), cache_, it);
                ++hits_;
                return &cache_.front().second;
            }
        }
        std::vector<uint8_t> bytes(hunk_bytes_);
        const chd_error e = chd_read(file_, number, bytes.data());
        if (e != CHDERR_NONE) {
            std::fprintf(stderr, "[te.io] disc image: CHD hunk %u not read (%s)\n", number, chd_error_string(e));
            return nullptr;
        }
        ++decoded_;
        cache_.emplace_front(number, std::move(bytes));
        if (cache_.size() > kKeep) cache_.pop_back();
        return &cache_.front().second;
    }

    static constexpr size_t kKeep = 16;
    chd_file* file_;
    uint32_t hunk_bytes_;
    uint64_t logical_;
    std::string path_;
    mutable std::list<std::pair<uint32_t, std::vector<uint8_t>>> cache_;
    mutable std::mutex lock_;
    mutable uint64_t decoded_ = 0, hits_ = 0;
};

std::shared_ptr<IsoFs::ImageSource> OpenChd(const std::string& path, std::string* error, bool* handled) {
    *handled = IsChdFile(path);
    if (!*handled) return nullptr;   // an ISO / BIN: the engine's own reader
    chd_file* file = nullptr;
    const chd_error e = chd_open(path.c_str(), CHD_OPEN_READ, nullptr, &file);
    if (e != CHDERR_NONE) {
        if (error) *error = path + ": the CHD could not be opened (" + chd_error_string(e) + ")";
        return nullptr;
    }
    const chd_header* h = chd_get_header(file);
    if (!h || h->hunkbytes == 0 || h->hunkbytes % IsoFs::kSectorSize != 0 || (h->unitbytes != 0 && h->unitbytes != IsoFs::kSectorSize)) {
        if (error)
            *error = path + ": a CD CHD (" + std::to_string(h ? h->unitbytes : 0) + "-byte units): make it with `chdman createdvd` "
                     "(the PS2 DVD layout, 2048-byte sectors)";
        chd_close(file);
        return nullptr;
    }
    std::fprintf(stderr, "[te.io] disc image: CHD %s (%llu bytes of sectors, %u hunks of %u bytes)\n", path.c_str(),
                 (unsigned long long)h->logicalbytes, h->totalhunks, h->hunkbytes);
    return std::make_shared<ChdSource>(file, *h, path);
}

}  // namespace
#endif

void InstallChdSupport() {
#if TE_HAVE_CHDR
    IsoFs::SetImageSourceFactory(&OpenChd);
#endif
}

}  // namespace te::io
