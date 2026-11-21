# Apps

This repository holds two independent apps. Each app has its own folder, with one sub-folder per platform.

| App | What it does | Android | Windows |
|---|---|---|---|
| [**Spatia**](Spatia/) | Spatial audio and sound enhancer for every app | [`Spatia/android`](Spatia/android/README.md) | [`Spatia/windows`](Spatia/windows/README.md) |
| [**ScreenLink**](ScreenLink/README.md) | Cast your phone's screen to your laptop, and use the phone as a keyboard for the laptop | [`ScreenLink/android`](ScreenLink/android/) | [`ScreenLink/windows`](ScreenLink/windows/) |

Spatia's audio engine lives once, in `Spatia/android/core-dsp` (Kotlin Multiplatform): the Android app uses it directly and the
Windows app calls it as `spatia_dsp.dll`.

CI workflows live in `.github/workflows/` (where GitHub requires them), one per app and platform:
`spatia-android.yml`, `spatia-windows.yml`, `screenlink-android.yml`, `screenlink-windows.yml`.
