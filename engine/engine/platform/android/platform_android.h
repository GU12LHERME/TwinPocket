#pragma once

namespace ps2r::platform::android {

// The caller supplies the application's private files directory. Android app
// integration will pass Context.getFilesDir()/SDL_GetPrefPath() in phase 3B.
bool Initialize(const char* private_files_root);
void Shutdown();

}  // namespace ps2r::platform::android
