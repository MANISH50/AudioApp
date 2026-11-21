# Changelog (Windows)

## 0.1.0
- First Windows release, with feature parity with Android 0.4.0's built-in player: modes, environments with an intensity meter, spatial 3D, EQ and tone, dynamics, headphone correction, room impulse response, hearing check, A/B bypass, meters, sleep timer and the sample track.
- Uses the same audio engine as Android (Kotlin Multiplatform → `spatia_dsp.dll`).
- Output detection with per-device profiles and safety limits; exclusive (bit-perfect) mode.
- Per-user `.exe` installer (Inno Setup; no admin rights) and a portable zip, published as GitHub Release assets.
- Not yet: enhancing other apps (next release), head tracking, IAMF, and code signing.
