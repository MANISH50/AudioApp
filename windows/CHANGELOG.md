# Changelog (Windows)

## 0.1.1
- **Fix:** Spatia could install but never open, with no window and no message. Startup now uses the standard WinUI path (App.xaml/MainWindow.xaml). Any startup failure is written to `%LOCALAPPDATA%\Spatia\logs\spatia.log` and shown in a Windows dialog; the app never keeps running invisibly.
- **New app icon** for the app, its window, the taskbar, the Start menu and the installer.
- **Tested on Windows before release:** CI installs the app with the real installer, opens a visible window, visits every page, plays the sample, changes the environment, takes screenshots, checks the log and uninstalls.
- **Windows security checks** in CI: BinSkim binary analysis, vulnerable-package scan, .NET security analyzers, Defender scan, DLL search-path hardening, and optional Authenticode signing. See SECURITY.md.

## 0.1.0
- First Windows release, with feature parity with Android 0.4.0's built-in player: modes, environments with an intensity meter, spatial 3D, EQ and tone, dynamics, headphone correction, room impulse response, hearing check, A/B bypass, meters, sleep timer and the sample track.
- Uses the same audio engine as Android (Kotlin Multiplatform → `spatia_dsp.dll`).
- Output detection with per-device profiles and safety limits; exclusive (bit-perfect) mode.
- Per-user `.exe` installer (Inno Setup; no admin rights) and a portable zip, published as GitHub Release assets.
- Not yet: enhancing other apps (next release), head tracking, IAMF, and code signing.
