# Spatia for Windows

Spatia is a sound enhancer for Windows 10 (version 2004 or later) and Windows 11, x64. It shapes the sound of **every app** (YouTube, Netflix, Spotify, games, any media player) through [Equalizer APO](https://sourceforge.net/projects/equalizerapo/), a free, open-source system-wide audio add-on. It's built with C#, .NET 10 and WinUI 3, on the **same audio engine as the Android app**: `android/core-dsp`, compiled by Kotlin/Native to `spatia_dsp.dll`.

## Install

1. Install **Equalizer APO** from its official page: <https://sourceforge.net/projects/equalizerapo/>. In its **Device Selector**, tick your speakers/headphones, then restart Windows.
2. Download **`Spatia-Setup-<version>-x64.exe`** from the repository's **Releases** page (tags `windows-v*`) and run it. It installs for your user account only, into `%LOCALAPPDATA%\Programs\Spatia`, with no administrator rights. Uninstall from **Settings → Apps**; you'll be asked whether to keep your sound settings, and Spatia disconnects from Equalizer APO.
3. Open Spatia. On **Home**, choose **Connect**. Windows asks for permission once: Spatia adds one line (`Include: Spatia\spatia.txt`) to Equalizer APO's `config.txt` (a backup is kept as `config.txt.spatia-backup`) and lets your account write its own settings folder `config\Spatia`. After that every change applies to all apps within a moment, with no further prompts.
4. Press **Test sound** to hear what all apps now get; turn **Spatia sound** off and on to compare.

The installer isn't code-signed yet, so Windows SmartScreen may warn: choose **More info → Run anyway**. Prefer no installer? The same release has `Spatia-Windows-x64.zip`. Extract it and run `Spatia.exe`.

## If Spatia doesn't start

Spatia shows a message and writes `%LOCALAPPDATA%\Spatia\logs\spatia.log` when it can't start. The log contains versions and startup steps only, with no personal data. Please send that file with any report.

## Features

Everything below applies to all apps through Equalizer APO.

| Feature | Status |
|---|---|
| Modes: Music, Movie, Gaming, Podcast/Voice, Night, Custom; Spatia on/off | ✅ same presets as Android |
| 10-band EQ, bass/treble/preamp, voice clarity, equal-loudness compensation, hearing compensation, headphone correction (AutoEq) | ✅ exact: the engine's biquads are written as Equalizer APO filters |
| Environments: Hall, Cinema, Park, Bathroom, Echoing, Cathedral, Stadium, Club, Living room, Tunnel, with an intensity meter | ✅ the engine renders the space into an impulse response (original sound kept, room added on top) that Equalizer APO convolves |
| Room impulse response import | ✅ mixed into the same impulse response |
| Width, mono, balance | ✅ channel matrices |
| Spatial 3D, crossfeed | ◐ approximated with stereo width, room and a broadband blend |
| Night compression | ◐ approximated (Equalizer APO has no compressor): quieter level plus a gentle loudness curve |
| Loudness normalisation, psychoacoustic bass, head tracking | ❌ not possible system-wide with Equalizer APO / no Windows API |
| Output detection: speakers, wired, Bluetooth, USB, HDMI/display, with manual override and per-device memory | ✅ |
| Safety: negative preamp offsets every boost so all-app sound never clips; high-volume warning (stricter for headphones) | ✅ |
| Test sound (4 s of the engine's sample music, unprocessed by Spatia itself) | ✅ |

The built-in file player and sample-track player of 0.1.x were removed in 0.2.0.

### How it works

`Spatia.exe` turns your settings into `config\Spatia\spatia.txt` (`Preamp`, `Filter`, `Copy`, `Convolution` lines) plus `config\Spatia\env_<hash>.wav` when a space is selected, written atomically and debounced (~150 ms). Equalizer APO notices the change and reloads. The filter list, preamp and matrices come from the shared engine (`spatia_apo_plan`), and the impulse response is rendered by the engine's `EnvironmentReverb` (`spatia_apo_render_ir`). For testing, `SPATIA_APO_DIR` points Spatia at another config folder.

## Build

Requirements: JDK 21, .NET SDK 10, and Windows to publish the app. The engine and tests also build on Linux.

```
cd Spatia/android && ./gradlew :core-dsp:allTests :core-dsp:linkReleaseSharedMingwX64   # builds spatia_dsp.dll
cd ../windows && dotnet test tests/Spatia.Core.Tests                                # tests against the real engine
dotnet publish src/Spatia.App -c Release -o out/Spatia                           # on Windows
```

The Kotlin/Native toolchain cross-compiles `spatia_dsp.dll` on Linux, which is how CI builds it. It depends only on `KERNEL32.dll` and `msvcrt.dll`.

### Layout

- `src/Spatia.Core`: interop with the engine (LibraryImport), settings models and storage, profile resolution, endpoint classification, strings, and the Equalizer APO integration (`Apo/`: locator, connect/disconnect, config writer).
- `src/Spatia.Audio`: device and volume monitoring, and the tone/test-sound player (NAudio WASAPI, shared mode).
- `src/Spatia.UI`: every page, the controller and UI helpers, built in C# (no .xaml files), so it compiles and is analysed on any OS.
- `src/Spatia.App`: the small WinUI 3 exe (App.xaml with standard startup, plus the window, icon and error reporting). `Spatia.exe --apo-connect <SID>` / `--apo-disconnect` run the Equalizer APO connect helper and exit before any UI.
- `assets/`: the app icon (`spatia-icon.svg`, rendered to `Spatia.ico` by `make-icon.py`).
- `tests/ui/ui-smoke.ps1`: the end-to-end UI test CI runs on Windows.
- `tests/Spatia.Core.Tests`: xUnit tests covering the engine through its C API, chain behaviour on the sample track, the logic, the settings store and string completeness.

### Localisation

All text is in `src/Spatia.UI/Strings/en-US/Resources.resw`. To add a language, copy that file to `Strings/<culture>/Resources.resw`, translate the values, and add one `EmbeddedResource` line in `Spatia.UI.csproj`. Missing keys fall back to English, and a test fails if code uses a key that doesn't exist.
