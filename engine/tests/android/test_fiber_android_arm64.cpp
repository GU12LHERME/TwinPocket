#include "engine/platform/posix/fiber_posix.h"
#include <cstdio>
#include <cmath>
using namespace ps2r::platform;
static Fiber g_main, g_a, g_b; static int counter = 0;
static void BodyA(void* arg) { int base = *(int*)arg; for (;;) { double d = std::sqrt((double)base); counter += base + (int)d; FiberPosix::Switch(g_b); } }
static void BodyB(void*) { for (;;) { counter *= 2; FiberPosix::Switch(g_main); } }
int main() {
  g_main = FiberPosix::Current(); int base = 9;
  g_a = FiberPosix::Create(BodyA, &base, 128*1024); g_b = FiberPosix::Create(BodyB, nullptr, 128*1024);
  register double keep asm("d8") = 1.5; long check = 42;
  for (int i = 0; i < 5; ++i) FiberPosix::Switch(g_a);
  asm volatile("" :: "w"(keep));
  std::printf("counter=%d keep=%g check=%ld\n", counter, keep, check);
  FiberPosix::Destroy(g_a); FiberPosix::Destroy(g_b);
  return (counter == 2*(2*(2*(2*(2*12+12)+12)+12)+12) && keep == 1.5) ? 0 : 1;
}
