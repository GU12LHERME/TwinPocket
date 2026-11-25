**English** | [Português (Brasil)](README.pt-BR.md)

# TwinPocket

Native Android ARM64 port of Crash Twinsanity (PS2), not an emulator. The game code is compiled for the device and runs using your own disc image. Developed and tested on the Retroid Pocket 6.

> No game data is included in the repository or APK. Bring your own ISO or CHD disc image.

## Download

[Download the latest APK](https://github.com/GU12LHERME/TwinsanityPocket/releases/latest) · [GitHub Actions](https://github.com/GU12LHERME/TwinsanityPocket/actions) · [Release notes (Portuguese)](android/game/NOTAS-DA-VERSAO.txt)

| Requirement | Details |
|---|---|
| Android | 7.0 or later |
| Architecture | arm64-v8a |
| Graphics | OpenGL ES 3.2 |
| Game | Crash Twinsanity ISO or CHD (USA tested) |
| Package | com.twinpocket.android |

Updates from 1.8.1 onward use the same Android package and signing configuration. Back up saves before updating.

## Usage

1. Open the app and select your ISO or CHD in the Play tab. The app copies the image into its own storage.
2. Start the game. Main menu → quit game returns to the launcher.

The launcher supports touch and XInput controllers, graphics settings, saves, system settings and diagnostic reports.

## Features and performance

- Internal resolution 1x, 2x or 3x; 4:3 or native 16:9; 30 or 60 FPS; VSync.
- Anisotropic filtering, FXAA and adjustable level of detail.
- Original, Balanced and Quality presets.
- Performance reports, crash diagnostics and session history.

Project reports on the Retroid Pocket 6 show stable 60 FPS at 3x resolution, 16x anisotropic filtering and long detail distance. Other devices and PAL disc images have not been tested.

## Known limitations

A short startup pause before the title screen and higher CPU usage during video cutscenes. The reported unexpected heating on the Start screen still needs targeted validation; the gameplay temperature measurements do not establish menu behavior.

## Development

The [twinsanity-reversed](https://github.com/Smartkin/twinsanity-reversed) decompilation is compiled to wasm32 and translated to C using wasm2c, running on NativeEE with OpenGL ES 3.2, OpenSL ES and SDL2. The Android launcher uses Java.

| Directory | Purpose |
|---|---|
| android/game/ | Android launcher and APK build |
| runner/ | Native entry points and diagnostics |
| platform/nativeee/host/ | Game-to-engine bridge |
| engine/ | NativeEE engine |
| game/ | Game decompilation |

See [Android build instructions](android/game/README.md) and [original upstream README](docs/TwinsanityEvolved-README.md).

## Releases

Update the app version and release notes, then create a matching numeric version tag. GitHub Actions publishes the signed ARM64 APK and SHA256SUMS.txt. Existing releases are preserved.

## Contributing and credits

See [CONTRIBUTING.md](CONTRIBUTING.md).

Based on [TwinsanityEvolved](https://github.com/menaman123/TwinsanityEvolved), NativeEE and twinsanity-reversed. See [MIT license](LICENSE), [FFmpeg license](FFmpeg-LICENSE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

Crash Twinsanity belongs to its respective rights holders. This project is unaffiliated and distributes no game data.

For detailed measurements and technical information, see the [complete Portuguese documentation](README.pt-BR.md).


## Help and security

[FAQ](docs/FAQ.md) · [Troubleshooting / Solução de problemas](docs/TROUBLESHOOTING.md) · [Security / Segurança](SECURITY.md)

**Migration from 1.8.0 or earlier:** Export saves before installing 1.8.1, then import them in TwinPocket. The new Android package is a separate app; select the game image and settings again.
