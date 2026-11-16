# RESEARCH — Spatia (Nov 2026)

**IAMF / Eclipsa Audio.** IAMF is an AOM royalty-free, codec-agnostic immersive format (Opus/AAC/FLAC/PCM payloads). Media3 ships `media3-decoder-iamf` (since 1.5.0), but it needs libiamf built locally (NDK); it is not on Maven with natives. Android 16 (API 36) adds `MediaFormat.MIMETYPE_AUDIO_IAMF` and IAMF `AudioFormat` encodings behind a feature flag, so platform decoders are device-dependent. **Decision:** probe `MediaCodecList` for `audio/iamf`; play if present, otherwise show a clear "not supported on this device" message.

**HRTF / SOFA.** SADIE II's SOFA files carry University of York copyright, and the license in the current record is unclear. The original SADIE is Apache-2.0. MIT KEMAR is free to use with credit. HUTUBS is CC-BY. **Decision:** don't bundle a dataset. Use a parametric spherical-head model (Brown–Duda: ITD + head-shadow filter), which has no licensing risk.

**Spatializer** (API 32+): reports availability, enabled state and immersion level for multichannel content. Head-tracker APIs are API 33+ and need a supported headset. It cannot be forced on, and it doesn't process stereo. We read its state; our own DSP covers everything else.

**AudioEffect.** Attaching to global session 0 is deprecated and unreliable. The documented route: a player broadcasts `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` with its session id, and the controller attaches `DynamicsProcessing` (API 28+: pre-EQ, multiband compressor, limiter, input gain) or `LoudnessEnhancer`. API 26+ blocks manifest receivers for implicit broadcasts, so a runtime-registered receiver in a foreground service is needed. API 34+ requires a declared FGS type (`specialUse`).

**Media3.** A custom `AudioProcessor` on ExoPlayer gives full float-PCM DSP for in-app playback.

**System-wide limits.** We can't touch apps that don't broadcast their session (many streaming apps and games, and all calls). Spatial/HRTF effects aren't possible system-wide. `AudioMixerAttributes` bit-perfect USB output needs API 34+.
