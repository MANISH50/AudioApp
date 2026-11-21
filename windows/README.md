# Spatia for Windows

Spatia is a spatial audio player and sound enhancer for Windows 10 (version 2004 or later) and Windows 11, x64. It's built with C#, .NET 10 and WinUI 3, on the **same audio engine as the Android app**. That engine is `android/core-dsp`, compiled by Kotlin/Native to `spatia_dsp.dll`.

## Run it

1. Download `Spatia-Windows-x64.zip` from the latest successful **Windows** workflow run (GitHub → Actions → Windows → Artifacts).
2. Extract the whole zip into a folder and run `Spatia.exe`. No installer and no administrator rights are needed. .NET and the Windows App SDK are bundled.
3. Windows SmartScreen may warn because the app isn't code-signed yet: choose **More info → Run anyway**.

## Features

| Feature | Status |
|---|---|
| Player: FLAC, MP3, AAC/M4A, WAV (Media Foundation), Opus (built-in decoder) | ✅ |
| IAMF | ❌ Windows has no IAMF decoder; the app shows a clear message |
| Built-in sample track (groove, voice, claps with silence, left–right sweep) | ✅ |
| Modes: Music, Movie, Gaming, Podcast/Voice, Night, Custom | ✅ same presets as Android |
| Environments: Hall, Cinema, Park, Bathroom, Echoing, Cathedral, Stadium, Club, Living room, Tunnel, with an intensity meter | ✅ same engine |
| 10-band EQ, bass/treble/preamp, voice clarity, loudness normalisation, night compression, equal-loudness compensation | ✅ |
| Spatial 3D (width, room, surround upmix, 5.1/7.1 virtualisation), crossfeed, psychoacoustic bass | ✅ |
| Headphone correction import (AutoEq / EqualizerAPO), room impulse response, hearing check | ✅ |
| A/B loudness-matched bypass, meters and spectrum, mono/balance, sleep timer | ✅ |
| Output detection: speakers, wired, Bluetooth, USB, HDMI/display, with manual override and per-device memory | ✅ |
| Safety: always-on limiter, per-output gain caps, high-volume warning (stricter for headphones), smooth ramps | ✅ |
| Exclusive mode (bit-perfect WASAPI) | ✅ when the device accepts the file's format; bypasses processing |
| Head tracking | ❌ there's no standard Windows API for headphone head trackers |
| Enhancing other apps (system-wide) | ❌ next release, via a user-installed virtual audio device |

## Build

Requirements: JDK 21, .NET SDK 10, and Windows to publish the app. The engine and tests also build on Linux.

```
cd android && ./gradlew :core-dsp:allTests :core-dsp:linkReleaseSharedMingwX64   # builds spatia_dsp.dll
cd windows && dotnet test tests/Spatia.Core.Tests                                # 46 tests against the real engine
dotnet publish src/Spatia.App -c Release -o out/Spatia                           # on Windows
```

The Kotlin/Native toolchain cross-compiles `spatia_dsp.dll` on Linux, which is how CI builds it. It depends only on `KERNEL32.dll` and `msvcrt.dll`.

### Layout

- `src/Spatia.Core`: interop with the engine (LibraryImport), settings models and storage, profile resolution, endpoint classification, format checks and strings.
- `src/Spatia.Audio`: NAudio WASAPI output, Media Foundation decoding, the Concentus Opus decoder, device and volume monitoring, and the hearing-check tone player.
- `src/Spatia.App`: the WinUI 3 app. The UI is built in C# (no XAML files), so every page compiles and is checked on any OS.
- `tests/Spatia.Core.Tests`: xUnit tests covering the engine through its C API, chain behaviour on the sample track, the logic, the settings store and string completeness.

### Localisation

All text is in `src/Spatia.App/Strings/en-US/Resources.resw`. To add a language, copy that file to `Strings/<culture>/Resources.resw`, translate the values, and add one `EmbeddedResource` line in `Spatia.App.csproj`. Missing keys fall back to English, and a test fails if code uses a key that doesn't exist.
