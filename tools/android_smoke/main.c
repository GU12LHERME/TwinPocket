// Minimal NDK cross-compile sentinel; this is not an Android application.
#include <stdint.h>

#if !defined(__ANDROID__) || !defined(__aarch64__)
#error "Android smoke target must compile for Android ARM64"
#endif

#if !defined(__ANDROID_API__) || __ANDROID_API__ != 24
#error "Android smoke target must compile against Android API 24"
#endif

_Static_assert(sizeof(void*) == 8, "arm64-v8a requires 64-bit pointers");

int main(void) {
    return sizeof(uintptr_t) == 8 ? 0 : 1;
}
