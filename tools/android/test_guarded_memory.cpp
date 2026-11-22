// The Android game memory's guard (engine/ee/mem/main_memory.h MainMemoryGuardedAllocator), built on the CI's Linux with
// __ANDROID__ defined: the 32 MB zeroed and 64 KiB aligned, writable to its last byte, and every access the unchecked module
// could form outside it -- just past the end, inside the first 4 GiB, and near 8 GiB (address + offset) -- faults.
#include "engine/ee/mem/main_memory.h"
#include <csignal>
#include <csetjmp>
#include <cstring>
static sigjmp_buf jb;
static void h(int){ siglongjmp(jb,1); }
int main(){
  MainMemory m;
  uint8_t* b=m.Data();
  printf("size %zu aligned %d zero %d\n", m.Size(), int(((uintptr_t)b & 65535)==0), int(b[0]==0 && b[MainMemory::kSize-1]==0));
  b[MainMemory::kSize-1]=7;
  signal(SIGSEGV,h);
  int faults=0;
  uint64_t offs[]={MainMemory::kSize, MainMemory::kSize+12345, 0xFFFFFFFFull, 0x1FFFFFFF0ull};
  for(uint64_t o: offs){ if(sigsetjmp(jb,1)==0){ volatile uint8_t x=b[o]; (void)x; } else faults++; }
  printf("faults %d/4 -- %s\n", faults, faults == 4 ? "test_guarded_memory: ok" : "test_guarded_memory: FAILED");
  return faults==4?0:1;
}
