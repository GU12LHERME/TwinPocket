// platform/posix/fiber_android_arm64.cpp -- the FiberPosix backend for Android arm64-v8a.
//
// The macOS / Linux FiberPosix (fiber_posix.cpp) switches with ucontext's getcontext / makecontext / swapcontext,
// which Android's bionic libc does not implement. This file is the same class (same header, same contract: one record
// per fiber plus one implicit record per host thread, mmap'd stacks with a PROT_NONE guard page below them) with the
// switch written for AAPCS64: the callee-saved state (sp, x19-x30, d8-d15) is stored into the running record and
// loaded from the target's -- the register layout runner/te_fiber_switch.S uses for the game fiber, under names of its
// own so the two never clash in one image.
#include "engine/platform/posix/fiber_posix.h"

#if !defined(__aarch64__)
#error "fiber_android_arm64.cpp is the AArch64 switch (the Android lane builds arm64-v8a only)"
#endif

#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

extern "C" {
// The callee-saved register file of a suspended context (AAPCS64). Offsets are the asm's below.
struct Ps2rFiberA64Context {
    uint64_t sp;        //   0
    uint64_t x[12];     //   8: x19..x30 (x29 = fp, x30 = lr: the resume address)
    uint64_t d[8];      // 104: d8..d15
};
void ps2r_fiber_a64_switch(Ps2rFiberA64Context* save, const Ps2rFiberA64Context* load);
void ps2r_fiber_a64_trampoline();
}
static_assert(sizeof(Ps2rFiberA64Context) == 168, "Ps2rFiberA64Context layout (the asm's offsets)");

asm(R"(
    .text
    .balign 4
    .globl ps2r_fiber_a64_switch
    .type ps2r_fiber_a64_switch, %function
ps2r_fiber_a64_switch:
    mov     x2, sp
    str     x2, [x0, #0]
    stp     x19, x20, [x0, #8]
    stp     x21, x22, [x0, #24]
    stp     x23, x24, [x0, #40]
    stp     x25, x26, [x0, #56]
    stp     x27, x28, [x0, #72]
    stp     x29, x30, [x0, #88]
    stp     d8,  d9,  [x0, #104]
    stp     d10, d11, [x0, #120]
    stp     d12, d13, [x0, #136]
    stp     d14, d15, [x0, #152]
    ldr     x2, [x1, #0]
    mov     sp, x2
    ldp     x19, x20, [x1, #8]
    ldp     x21, x22, [x1, #24]
    ldp     x23, x24, [x1, #40]
    ldp     x25, x26, [x1, #56]
    ldp     x27, x28, [x1, #72]
    ldp     x29, x30, [x1, #88]
    ldp     d8,  d9,  [x1, #104]
    ldp     d10, d11, [x1, #120]
    ldp     d12, d13, [x1, #136]
    ldp     d14, d15, [x1, #152]
    ret
    .size ps2r_fiber_a64_switch, .-ps2r_fiber_a64_switch

    // A prepared fiber's first entry: x19 = the C entry, x20 = its argument. The entry never returns.
    .globl ps2r_fiber_a64_trampoline
    .type ps2r_fiber_a64_trampoline, %function
ps2r_fiber_a64_trampoline:
    mov     x0, x20
    blr     x19
    brk     #0
    .size ps2r_fiber_a64_trampoline, .-ps2r_fiber_a64_trampoline
)");

namespace ps2r::platform {
namespace {

struct FiberRecord {
    Ps2rFiberA64Context ctx{};
    uint8_t* mapping = nullptr;   // null for a host thread's own record
    size_t   mapping_size = 0;    // guard + usable stack
    void   (*entry)(void*) = nullptr;
    void*    arg = nullptr;
};

thread_local FiberRecord* t_current = nullptr;

size_t PageSize() {
    static const size_t page = [] {
        const long p = sysconf(_SC_PAGESIZE);
        return p > 0 ? static_cast<size_t>(p) : static_cast<size_t>(4096);
    }();
    return page;
}

size_t RoundUpToPage(size_t n) {
    const size_t page = PageSize();
    return (n + page - 1) / page * page;
}

// A host thread's record: its context is filled by the first switch away from it.
FiberRecord* HostRecord() {
    if (!t_current) t_current = new FiberRecord();
    return t_current;
}

extern "C" void Ps2rFiberA64Entry(void* opaque) {
    auto* r = static_cast<FiberRecord*>(opaque);
    r->entry(r->arg);
    std::fprintf(stderr, "platform: fiber entry returned; a fiber body must park forever\n");
    std::abort();
}

}  // namespace

Fiber FiberPosix::Create(void (*fn)(void*), void* arg, size_t stack) {
    Fiber f;
    if (!fn) return f;
    if (stack < 64 * 1024) stack = 64 * 1024;
    stack = RoundUpToPage(stack);
    const size_t guard = PageSize();
    auto* r = new FiberRecord();
    r->mapping_size = guard + stack;
    void* m = mmap(nullptr, r->mapping_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (m == MAP_FAILED) {
        delete r;
        return f;
    }
    r->mapping = static_cast<uint8_t*>(m);
    // The lowest page is the guard: any access below the usable stack faults.
    if (mprotect(r->mapping, guard, PROT_NONE) != 0) {
        munmap(r->mapping, r->mapping_size);
        delete r;
        return f;
    }
    r->entry = fn;
    r->arg = arg;
    const uintptr_t top = reinterpret_cast<uintptr_t>(r->mapping + r->mapping_size) & ~uintptr_t(15);   // 16-byte aligned
    r->ctx.sp = top;
    r->ctx.x[0] = reinterpret_cast<uint64_t>(&Ps2rFiberA64Entry);          // x19
    r->ctx.x[1] = reinterpret_cast<uint64_t>(r);                           // x20
    r->ctx.x[10] = 0;                                                      // x29: no frame above the entry
    r->ctx.x[11] = reinterpret_cast<uint64_t>(&ps2r_fiber_a64_trampoline); // x30: the first switch "returns" there
    f.handle = r;
    return f;
}

void FiberPosix::Switch(Fiber to) {
    auto* target = static_cast<FiberRecord*>(to.handle);
    if (!target) return;
    FiberRecord* from = HostRecord();
    if (from == target) return;
    t_current = target;
    ps2r_fiber_a64_switch(&from->ctx, &target->ctx);
    // Resumed: whoever switched back to us set t_current before switching.
    t_current = from;
}

Fiber FiberPosix::Current() {
    Fiber f;
    f.handle = HostRecord();
    return f;
}

void FiberPosix::Destroy(Fiber f) {
    auto* r = static_cast<FiberRecord*>(f.handle);
    if (!r || !r->mapping) return;   // host records are never destroyed
    munmap(r->mapping, r->mapping_size);
    delete r;
}

}  // namespace ps2r::platform
