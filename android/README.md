# Spatia

Spatia is a spatial audio player and audio enhancer for Android 10 and later (minSdk 29). It uses only documented Android audio APIs and open, royalty-free technology. All DSP is original code in `core-dsp/` (pure Kotlin, unit-tested on the JVM). No HRTF dataset is bundled; spatial audio uses a parametric spherical-head model. See `RESEARCH.md` for the reasoning.

## Build

Requirements: JDK 17 or later, Android SDK platform 35 and build-tools 35.

```
./gradlew :core-dsp:test :app:testDebugUnitTest   # JVM unit tests (DSP, limiter, profiles, parsers)
./gradlew :app:lintDebug :app:assembleRelease     # lint + R8-minified release APK (unsigned)
./gradlew :app:connectedDebugAndroidTest          # on-device test; needs a device or emulator
```

Dependencies are pinned in `gradle/libs.versions.toml` and checked against SHA-256 hashes in `gradle/verification-metadata.xml`. Release signing isn't committed: sign with your own keystore.

## What works on which Android version

| Feature | 10–11 (29–30) | 12 (31) | 12L (32) | 13 (33) | 14 (34) | 15–16 (35–36) |
|---|---|---|---|---|---|---|
| Built-in player: FLAC, Opus, AAC, MP3, WAV | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| IAMF (inside MP4 only) | ❌ | ❌ | ❌ | ❌ | ❌ | Only if the device ships an `audio/iamf` decoder (some Android 16 devices) |
| Modes, 10-band EQ, bass, treble, voice clarity, loudness normalisation, night compression | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Spatial 3D (width, room size, surround upmix, 5.1/7.1 virtualisation) in the player | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Environments: Hall, Cinema, Park, Bathroom, Echoing, Cathedral, Stadium, Club, Living room, Tunnel, with an intensity meter | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Head tracking (own renderer) | ❌ | ❌ | ❌ | ✅ with a headset that exposes a head-tracker sensor | ✅ same | ✅ same |
| Platform Spatializer status (read-only) | n/a | n/a | ✅ | ✅ | ✅ | ✅ |
| Output detection + per-output/per-device profiles | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Limiter, gain caps, high-volume warning, parameter ramps | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Headphone correction import (AutoEq / EqualizerAPO text) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Crossfeed, psychoacoustic bass, equal-loudness compensation, hearing profile, room IR convolution, A/B, meters, mono/balance, sleep timer | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Bit-perfect USB DAC output | ❌ | ❌ | ❌ | ❌ | ✅ if the DAC supports it | ✅ same |
| Enhancing other apps (EQ, tone, correction, per-app boost, night, limiter) | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| Environments for other apps (approximated with Android's EnvironmentalReverb) | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| Global mode: all apps through session 0 (opt-in, deprecated route) | ⚠️ device-dependent | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
| Spatial 3D / head tracking / crossfeed / imported room IR for other apps | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

⚠️ **System-wide limits.** Spatia can only process apps that broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Many local music players do. Most streaming, video, game and call apps don't, and those can't be enhanced. Global session 0 is deprecated and isn't used. On devices without the DynamicsProcessing effect, Spatia falls back to the basic Equalizer with no boost, because there's no limiter. The system-wide service has to be started from the app; it doesn't start on boot, which avoids an extra permission.

## Sample track

Tap **Play sample** on the Player tab to hear every feature without your own files. It's an original 24-second loop made by Spatia:

- 0–8 s: groove. Try the modes, EQ, bass and treble.
- 8–14 s: voice-like melody. Try Podcast/Voice, Voice clarity and Night.
- 14–20 s: claps separated by silence. Try Hall, Cinema and Echoing, and listen to the gaps.
- 20–24 s: left–right sweep. Try Width, Spatial 3D, Crossfeed and Balance.

## Environments

The Sound tab's **Environment** section places the music in a space. Each one is a parallel send effect: the original audio passes through unchanged at full level, and the room is added on top. The rooms use pre-delay, early reflections, an 8-line feedback delay network and, for some spaces, a ping-pong echo. The effect is band-limited so it never muddies the bass.

The **intensity meter** has its centre at each space's designed sound. Moving it left makes the room subtler, and fully left turns it off. Moving it right makes it louder (up to +6 dB) and longer (up to 1.5× the decay time). Echo feedback is hard-capped so echoes always fade away. The limiter still protects the output.

## Using Spatia with Apple Music, Spotify, YouTube and other apps

Android only lets one app change another app's sound through audio effects attached to that app's audio session. Spatia tries three routes, in this order:

1. **The app announces its session.** This is automatic once **Apps → Enhance other apps** is on. Pause and resume playback after turning it on.
2. **The app's Equaliser button.** Many players (including Apple Music on many phones: Settings → Playback → Equaliser) open the system's equaliser picker. Choose Spatia and it attaches to that app's session directly.
3. **Global mode (experimental).** Attaches to the global output mix (session 0). Android has deprecated this, so some phones ignore it, and it may clash with other equaliser apps.

The **Apps** tab shows what's happening right now, and the Sound tab warns when the audio playing isn't being enhanced. Through these routes you get EQ, tone, voice clarity, loudness compensation, headphone correction, night mode, per-app boost, the limiter, and environments rendered by Android's reverb. Android's reverb can't make discrete repeating echoes, so Echoing, Tunnel, Stadium and the Cinema slap sound softer than in Spatia's own player. Spatial 3D, head tracking, crossfeed and imported room responses need the audio samples themselves, so they're only possible in Spatia's player. If an app uses none of the three routes, only Spatia's player can be enhanced.

## Known gaps

- **Not run on a device yet.** Everything was built, linted and unit-tested in CI-like conditions. The instrumented test and real-device listening still need to be done.
- No crossfade between tracks. Gapless playback relies on Media3's built-in gapless support for MP3/AAC/FLAC/Opus.
- ReplayGain is read from Vorbis comments (FLAC/Ogg/Opus, including R128) and ID3 `TXXX` frames (MP3). MP4/M4A iTunes-style gain atoms aren't read.
- Room impulse responses are held in memory only, so re-import after the app restarts.
- The listening-dose figure is an estimate from the volume setting and signal level. It isn't an SPL measurement. The hearing check isn't a medical test.
- Bit-perfect mode bypasses all Spatia processing, including the limiter.
- Spatial audio uses a generic head model, so the externalisation is milder than with personalised, measured HRTFs.
- Translations: English only, but every string is in `res/values/strings.xml` and ready to localise.

## Project layout

- `core-dsp/`: Android-free DSP and logic: biquads, limiter, compressor, loudness, binaural renderer, reverb, convolution, crossfeed, upmix, safety policy, presets, profile selection, parsers.
- `app/`: Compose UI, Media3 player and `AudioProcessor`, output detection, volume guard, head tracker, session effects service, device status.
