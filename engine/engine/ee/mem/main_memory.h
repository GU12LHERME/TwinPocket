#pragma once
// EE main RAM: 32 MB of physical memory. KUSEG (0x0xxxxxxx), KSEG0 (0x8...)
// and KSEG1 (0xA...) all map onto it through `addr & 0x1FFFFFFF`; the RAM
// itself is the low 32 MB of that physical window.
#include <cstddef>
#include <cstdint>
#include <vector>
#if defined(__vita__)
#include <malloc.h>
#include <cstdlib>
#endif
#if defined(__ANDROID__) && defined(__LP64__)
#include <sys/mman.h>
#include <cstdio>
#include <cstdlib>
#endif

#if defined(__vita__)
// The Vita: the 32 MB on a 4 KB boundary. newlib's malloc aligns to 8 bytes, which can put the game's 16- and 32-byte
// aligned data (the PS2's quadword vectors and matrices) across the Cortex-A9's 32-byte cache lines; on a page every
// guest alignment is the host's. Only the host address changes -- the game sees offsets into the block.
template <class T>
struct MainMemoryPageAllocator {
    using value_type = T;
    MainMemoryPageAllocator() = default;
    template <class U>
    MainMemoryPageAllocator(const MainMemoryPageAllocator<U>&) {}
    T* allocate(size_t n) {
        void* p = memalign(4096, n * sizeof(T));   // newlib's (the Vita's has no posix_memalign body)
        if (!p) std::abort();
        return static_cast<T*>(p);
    }
    void deallocate(T* p, size_t) { std::free(p); }
    template <class U>
    bool operator==(const MainMemoryPageAllocator<U>&) const { return true; }
    template <class U>
    bool operator!=(const MainMemoryPageAllocator<U>&) const { return false; }
};
#endif

#if defined(__ANDROID__) && defined(__LP64__)
// Android (64-bit): the 32 MB at the start of an 8 GiB reservation whose rest is inaccessible (PROT_NONE: address space
// only, no memory). The game module (wasm2c) addresses its memory as base + a 32-bit address + a 32-bit offset, so every
// access the game can make lands inside the reservation: inside the 32 MB as before, or on the inaccessible rest, where it
// faults (SIGSEGV) -- the same end as the checked build's trap ("out of bounds memory access" is fatal there too), never a
// read or write of someone else's memory. That is what lets the Android build compile the module's loads and stores
// without the per-access compare (CMake TE_ANDROID_UNCHECKED_MEMORY, tools/w2c_unchecked.py) and stay as safe: wasm2c's
// own guard-page memory, with the engine keeping the bytes (their address never changes; the module never grows memory).
// If the address space cannot be reserved (a device that refuses it), the block is an ordinary allocation and a line says
// that out-of-range accesses are not caught there.
template <class T>
struct MainMemoryGuardedAllocator {
    using value_type = T;
    static constexpr size_t kReserve = size_t(8) << 30;   // 4 GiB of address + 4 GiB of offset, as wasm2c's guard pages
    MainMemoryGuardedAllocator() = default;
    template <class U>
    MainMemoryGuardedAllocator(const MainMemoryGuardedAllocator<U>&) {}
    static size_t Rounded(size_t bytes) { return (bytes + 65535) & ~size_t(65535); }
    T* allocate(size_t n) {
        const size_t bytes = Rounded(n * sizeof(T));
        void* p = ::mmap(nullptr, kReserve + bytes, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
        if (p != MAP_FAILED && ::mprotect(p, bytes, PROT_READ | PROT_WRITE) == 0) {
            std::fprintf(stderr, "[twinsanityevolved] memory: the game's %zu MB in a guarded reservation (an access outside it faults)\n",
                         bytes >> 20);
            return static_cast<T*>(p);
        }
        if (p != MAP_FAILED) ::munmap(p, kReserve + bytes);
        std::fprintf(stderr, "[twinsanityevolved] memory: no guarded reservation on this device -- an ordinary block (out-of-range "
                             "accesses are not caught)\n");
        void* q = nullptr;
        if (::posix_memalign(&q, 65536, bytes) != 0 || !q) std::abort();
        unguarded() = q;
        return static_cast<T*>(q);
    }
    void deallocate(T* p, size_t n) {
        if (static_cast<void*>(p) == unguarded()) {
            unguarded() = nullptr;
            std::free(p);
            return;
        }
        ::munmap(p, kReserve + Rounded(n * sizeof(T)));
    }
    static void*& unguarded() {
        static void* q = nullptr;
        return q;
    }
    template <class U>
    bool operator==(const MainMemoryGuardedAllocator<U>&) const { return true; }
    template <class U>
    bool operator!=(const MainMemoryGuardedAllocator<U>&) const { return false; }
};
#endif

class MainMemory {
public:
    static constexpr uint32_t kSize = 32u * 1024u * 1024u;
    static constexpr uint32_t kPhysMask = 0x1FFFFFFFu;

    MainMemory();

    // Physical address of a KUSEG/KSEG0/KSEG1 virtual address.
    static uint32_t Phys(uint32_t addr) { return addr & kPhysMask; }

    // Host pointer for `size` bytes at physical `phys`, or nullptr when the
    // range is not entirely inside RAM.
    uint8_t* Pointer(uint32_t phys, size_t size);
    const uint8_t* Pointer(uint32_t phys, size_t size) const;

    uint8_t* Data() { return bytes_.data(); }
    const uint8_t* Data() const { return bytes_.data(); }
    size_t Size() const { return bytes_.size(); }

    void Clear();

private:
#if defined(__vita__)
    std::vector<uint8_t, MainMemoryPageAllocator<uint8_t>> bytes_;
#elif defined(__ANDROID__) && defined(__LP64__)
    std::vector<uint8_t, MainMemoryGuardedAllocator<uint8_t>> bytes_;
#else
    std::vector<uint8_t> bytes_;
#endif
};
