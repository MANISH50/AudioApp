# Spatia

Spatia is a system-wide sound enhancer for Android 10 and later (minSdk 29): it changes the sound of whatever the phone plays (YouTube, Netflix, Spotify, games). It has no player of its own. It uses only documented Android audio APIs and open, royalty-free technology. All DSP is original code in `core-dsp/` (pure Kotlin, unit-tested on the JVM). No HRTF dataset is bundled; spatial audio uses a parametric spherical-head model. See `RESEARCH.md` for the reasoning.

## Build

Requirements: JDK 17 or later, Android SDK platform 35 and build-tools 35.

```
./gradlew :core-dsp:test :app:testDebugUnitTest   # JVM unit tests (DSP, limiter, profiles, parsers)
./gradlew :app:lintDebug :app:assembleRelease     # lint + R8-minified release APK (unsigned)
./gradlew :app:connectedDebugAndroidTest          # on-device test; needs a device or emulator
```

Dependencies are pinned in `gradle/libs.versions.toml` and checked against SHA-256 hashes in `gradle/verification-metadata.xml`. Release signing isn't committed: sign with your own keystore.

## How it works

Android doesn't let one app read another app's audio. What it does allow is attaching platform **audio effects**. Spatia does this in two places:

1. **The global output mix (session 0).** This is on by default, and it's the only non-root way to reach YouTube, Netflix and other apps that never announce their audio. Android has deprecated this route, so whether it works depends on the phone's software.
2. **Sessions that apps announce.** Many music players announce their audio session, or open Spatia from their own Equaliser button, and these get their own copy of the effects plus a per-app boost.

On each of them Spatia runs:
- `DynamicsProcessing` for modes, the 10-band EQ, bass, treble, voice clarity, loudness compensation, headphone correction, hearing profile, night compression, balance and the limiter;
- `EnvironmentalReverb` (insert) for environments: Hall, Cinema, Park, Bathroom, Echoing and the rest, with the intensity meter;
- `Virtualizer` for Spatial and Width.

The **Home** tab has the On/Off switch and a **Play test sound** button. The test sound plays music the way any other app would, so you hear exactly what YouTube or Netflix would get.

Home also shows, for each effect, whether it's *Active*, *Blocked by phone*, *Taken over* (another sound enhancer has control) or *Not on this phone*, with exact steps for Xiaomi/Redmi/POCO, OPPO/realme, OnePlus, vivo/iQOO and Samsung. It also links to the app settings page where battery restrictions and Autostart are set.

Spatia starts again after a reboot if it was on.

⚠️ **Limits.**
- If a phone refuses the global mix, no non-root app can change YouTube or Netflix there. Only apps that announce their audio can be changed, and Home says so.
- Only one enhancer can control the global mix. Phones with their own Dolby/Mi Sound/Real Sound enhancer must have it turned off.
- Echoes from Android's reverb are softer than real repeating echoes.
- There's no surround upmix, head tracking or imported room response, because those need the audio samples, which Android doesn't give to other apps.

## Project layout

- `core-dsp/`: Android-free DSP and logic: biquads, limiter, compressor, loudness, binaural renderer, reverb, convolution, crossfeed, upmix, safety policy, presets, profile selection, parsers.
- `app/`: Compose UI, system-wide effects service (global mix + announced sessions), test sound, output detection, volume guard, device status.
