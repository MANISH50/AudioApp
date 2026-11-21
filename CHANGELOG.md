# Changelog

## 0.3.0
- Fix the gap reported in testing: modes and environments had no audible effect on Apple Music and other apps, with no explanation.
- **Apps → "What's happening now"** card: detects that another app is playing and says whether, and why, Spatia isn't processing it, with step-by-step fixes. The Sound tab shows a warning banner in the same situation.
- Spatia now answers other apps' **Equaliser** buttons (`ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`) and attaches to their session directly.
- **Global mode (experimental, opt-in)**: applies effects to the global output mix on phones that still allow it.
- **Environments for other apps**: Hall, Cinema and the other spaces are approximated with Android's EnvironmentalReverb (dry kept, room added). The intensity meter works the same way.
- New end-to-end test that runs audio through the real Media3 processor, proving modes and environments change the built-in player's output.

## 0.2.0
- New **Environment** section in the Sound tab with Hall (empty concert hall), Cinema (old single-screen theatre), Park, Bathroom, Echoing (canyon), Cathedral, Stadium, Club, Living room and Tunnel.
- Intensity meter: the centre is each space's designed default, left is subtler (fully left = off), right is stronger. Includes a "Reset to default" button.
- The original audio is never reduced. The room is added on top, band-limited to keep bass and vocals clean, and still goes through the safety limiter.
- Switching spaces fades out and back in over a few milliseconds, so there are no clicks.
- Settings saved by 0.1.0 load unchanged. Unknown future values fall back to their defaults instead of resetting all settings.
- Device Status lists environments. As with spatial audio, they work only in the built-in player.

## 0.1.0
- First release: built-in player, modes, 10-band EQ, spatial 3D with head tracking, output profiles with safety limits, system-wide enhancement for apps that announce their sessions, headphone correction, hearing profile, and the other extras listed in the README.
