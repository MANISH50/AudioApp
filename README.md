# Spatia

Spatial audio player and sound enhancer built on open, royalty-free technology.

| Folder | App | Status |
|---|---|---|
| [`android/`](android/README.md) | Android app (Kotlin, Jetpack Compose, Media3), plus the shared audio engine `android/core-dsp` | Released (0.4.0) |
| [`windows/`](windows/README.md) | Windows desktop app (C#, .NET 10, WinUI 3). It uses the same audio engine, compiled to `spatia_dsp.dll` | 0.1.0 (installer on the Releases page) |

The audio engine (EQ, environments, limiter, spatial 3D, presets, parsers and the sample track) lives once, in `android/core-dsp`. It's Kotlin Multiplatform: the Android app uses it directly, and the Windows app calls it as `spatia_dsp.dll`. A fix or tuning change there reaches both apps, and one test suite covers both.

CI workflows live in `.github/workflows/`, where GitHub requires them.
