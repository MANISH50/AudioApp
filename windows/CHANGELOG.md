# Changelog (Windows)

## 0.2.0
- **Spatia now changes the sound of every app** (YouTube, Netflix, Spotify, games, any media player) through [Equalizer APO](https://sourceforge.net/projects/equalizerapo/), free and open source. Previously modes and effects only applied to Spatia's own player, so choosing a mode changed nothing in other apps.
- **New Home page**: Equalizer APO status with the right action (Download, Connect with one Windows permission prompt, or Active ✓ with Disconnect), the Device Selector reminder, modes, Spatia on/off and a **Test sound** button (4 s of music, unprocessed by Spatia itself, so you hear exactly what all apps get).
- Settings are written to `config\Spatia\spatia.txt` within ~150 ms of any change: the engine's EQ/tone/voice/loudness/hearing/headphone-correction filters, a negative preamp so boosts never clip, width/mono/balance matrices, and environments rendered by the engine into an impulse response.
- Approximated system-wide (shown in the app): Night (no compressor in Equalizer APO), Spatial 3D and crossfeed. Not available system-wide: loudness normalisation, psychoacoustic bass.
- **Removed** the file player, the "Open file" and sample-track playback, meters, sleep timer, exclusive mode and the listening-dose tracker (they only worked for Spatia's own player).
- Uninstall disconnects Spatia from Equalizer APO.
- Engine C API version 2: `spatia_apo_plan`, `spatia_apo_render_ir`.

## 0.1.1
- **Fix:** Spatia could install but never open, with no window and no message. Startup now uses the standard WinUI path (App.xaml). Any startup failure is written to `%LOCALAPPDATA%\Spatia\logs\spatia.log` and shown in a Windows dialog; the app never keeps running invisibly.
- **New app icon** for the app, its window, the taskbar, the Start menu and the installer.
- **Tested on Windows before release:** CI installs the app with the real installer, opens a visible window, visits every page, plays the sample, changes the environment, takes screenshots, checks the log and uninstalls.
- **Windows security checks** in CI: BinSkim binary analysis, vulnerable-package scan, .NET security analyzers, Defender scan, DLL search-path hardening, and optional Authenticode signing. See SECURITY.md.

## 0.1.0
- First Windows release, with feature parity with Android 0.4.0's built-in player: modes, environments with an intensity meter, spatial 3D, EQ and tone, dynamics, headphone correction, room impulse response, hearing check, A/B bypass, meters, sleep timer and the sample track.
- Uses the same audio engine as Android (Kotlin Multiplatform → `spatia_dsp.dll`).
- Output detection with per-device profiles and safety limits; exclusive (bit-perfect) mode.
- Per-user `.exe` installer (Inno Setup; no admin rights) and a portable zip, published as GitHub Release assets.
- Not yet: enhancing other apps (next release), head tracking, IAMF, and code signing.
