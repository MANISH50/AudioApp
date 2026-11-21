# Spatia for Windows

Spatia is a spatial audio player and sound enhancer for Windows 10 (version 2004 or later) and Windows 11, x64. It's built with C#, .NET 10 and WinUI 3, on the **same audio engine as the Android app**. That engine is `android/core-dsp`, compiled by Kotlin/Native to `spatia_dsp.dll`.

## Install

1. Download **`Spatia-Setup-<version>-x64.exe`** from the repository's **Releases** page (tags `windows-v*`).
2. Run it. It installs for your user account only, into `%LOCALAPPDATA%\Programs\Spatia`, with no administrator rights. It adds a Start-menu entry, plus an optional desktop shortcut. Uninstall from **Settings → Apps**; you'll be asked whether to keep your sound settings.
3. The installer isn't code-signed yet, so Windows SmartScreen may warn: choose **More info → Run anyway**.

Prefer no installer? The same release has `Spatia-Windows-x64.zip`. Extract it and run `Spatia.exe`. Every CI run also attaches both files as workflow artifacts.

## If Spatia doesn't start

Spatia shows a message and writes `%LOCALAPPDATA%\Spatia\logs\spatia.log` when it can't start. The log contains versions and startup steps only, with no personal data. Please send that file with any report.

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
- `src/Spatia.UI`: every page, the controller and UI helpers, built in C# (no .xaml files), so it compiles and is analysed on any OS.
- `src/Spatia.App`: the small WinUI 3 exe (App.xaml with standard startup, plus the window, icon and error reporting).
- `assets/`: the app icon (`spatia-icon.svg`, rendered to `Spatia.ico` by `make-icon.py`).
- `tests/ui/ui-smoke.ps1`: the end-to-end UI test CI runs on Windows.
- `tests/Spatia.Core.Tests`: xUnit tests covering the engine through its C API, chain behaviour on the sample track, the logic, the settings store and string completeness.

### Localisation

All text is in `src/Spatia.App/Strings/en-US/Resources.resw`. To add a language, copy that file to `Strings/<culture>/Resources.resw`, translate the values, and add one `EmbeddedResource` line in `Spatia.App.csproj`. Missing keys fall back to English, and a test fails if code uses a key that doesn't exist.
