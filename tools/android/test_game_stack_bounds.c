/* tools/android/test_game_stack_bounds.c -- the Android lane's game-stack layout, checked under qemu-aarch64 in CI.
 *
 * ART (Android's Java runtime) checks the stack pointer of a native thread that calls into Java against the stack
 * bounds it recorded for that thread (pthread_getattr_np at attach): a frame below the recorded stack end is taken as a
 * stack overflow (java.lang.StackOverflowError). SDL calls Java from the game's code, which runs on the game fiber, so
 * the fiber's frames must lie inside the calling thread's stack. This test runs the runner's own fiber code
 * (runner/te_fiber.c + te_fiber_switch.S) both ways:
 *   1. the desktop layout: the fiber stack mapped at its fixed hint (0x310000000000) -- its frames are OUTSIDE the
 *      thread's stack bounds (what ART refused on the device);
 *   2. the Android layout (runner/te_android.cpp): a thread with a 160 MB stack, the 32 MB fiber stack carved out of it
 *      -- its frames are INSIDE, 1 MB of recursion deep included, and the callee-saved registers survive the switches.
 * Exit status 0 when layout 1 is outside and layout 2 inside, as expected. */
#define _GNU_SOURCE
#include <alloca.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "te_fiber.h"


static uintptr_t g_lo, g_hi;          /* the current thread's stack bounds */
static te_fiber_ctx g_host, g_game;
static int g_inside_top, g_inside_deep, g_depth_ok;

static void bounds(void) {
    pthread_attr_t a;
    void* lo = NULL;
    size_t sz = 0;
    pthread_getattr_np(pthread_self(), &a);
    pthread_attr_getstack(&a, &lo, &sz);
    pthread_attr_destroy(&a);
    g_lo = (uintptr_t)lo;
    g_hi = g_lo + sz;
}

static int inside(void) {
    const uintptr_t fp = (uintptr_t)__builtin_frame_address(0);
    return fp > g_lo + 64 * 1024 && fp < g_hi;   /* above ART's reserved band at the low end */
}

static int recurse(int n) {
    volatile char pad[1024];
    memset((void*)pad, n & 0xFF, sizeof pad);
    if (n == 0) { g_inside_deep = inside(); return pad[0]; }
    return recurse(n - 1) + pad[1];
}

static void game_main(void* arg) {
    (void)arg;
    g_inside_top = inside();
    g_depth_ok = recurse(1024) >= 0;    /* ~1 MB of frames */
    for (;;) te_fiber_switch(&g_game, &g_host);
}

static int run_fiber(void* stack, size_t size) {
    register uint64_t keep19 asm("x19") = 0x1919191919191919ull;
    register double keep8 asm("d8") = 8.5;
    te_fiber_prepare(&g_game, stack, size, game_main, NULL);
    te_fiber_switch(&g_host, &g_game);
    __asm__ volatile("" : "+r"(keep19), "+w"(keep8));
    return keep19 == 0x1919191919191919ull && keep8 == 8.5;
}

struct result { int top, deep, depth, regs; };

static void* android_layout(void* out) {
    struct result* r = (struct result*)out;
    bounds();
    const size_t region = (32u << 20) + (1u << 20);
    uint8_t* fiber = (uint8_t*)alloca(region);
    const uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    const uintptr_t lo = ((uintptr_t)fiber + page - 1) & ~(page - 1);
    mprotect((void*)lo, 64 * 1024, PROT_NONE);
    r->regs = run_fiber((void*)(lo + 64 * 1024), 32u << 20);
    r->top = g_inside_top;
    r->deep = g_inside_deep;
    r->depth = g_depth_ok;
    printf("android layout: thread stack [%#lx, %#lx), fiber stack [%#lx, %#lx): frames %s (top) / %s (1 MB deep), registers %s\n",
           (unsigned long)g_lo, (unsigned long)g_hi, (unsigned long)(lo + 64 * 1024), (unsigned long)(lo + 64 * 1024 + (32u << 20)),
           r->top ? "INSIDE" : "outside", r->deep ? "INSIDE" : "outside", r->regs ? "kept" : "LOST");
    return NULL;
}

int main(void) {
    /* 1. the desktop layout on the main thread */
    bounds();
    int at_hint = 0;
    void* mapped = te_fiber_map_stack(0x310000000000ull, 32u << 20, &at_hint);
    const int regs1 = mapped && run_fiber(mapped, 32u << 20);
    printf("desktop layout: thread stack [%#lx, %#lx), fiber stack at %p: frames %s, registers %s\n", (unsigned long)g_lo,
           (unsigned long)g_hi, mapped, g_inside_top ? "inside" : "OUTSIDE (ART: StackOverflowError on a JNI call)",
           regs1 ? "kept" : "LOST");
    const int desktop_outside = !g_inside_top;
    te_fiber_unmap_stack(mapped, 32u << 20);

    /* 2. the Android layout on a 160 MB thread */
    struct result r = {0, 0, 0, 0};
    pthread_attr_t a;
    pthread_attr_init(&a);
    pthread_attr_setstacksize(&a, 160u << 20);
    pthread_t t;
    pthread_create(&t, &a, android_layout, &r);
    pthread_join(t, NULL);
    pthread_attr_destroy(&a);
    const int ok = desktop_outside && r.top && r.deep && r.depth && r.regs && regs1;
    printf("test_game_stack_bounds: %s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
