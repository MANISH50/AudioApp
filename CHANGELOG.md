# Changelog

## 0.2.0
- New **Environment** section in the Sound tab with Hall (empty concert hall), Cinema (old single-screen theatre), Park, Bathroom, Echoing (canyon), Cathedral, Stadium, Club, Living room and Tunnel.
- Intensity meter: the centre is each space's designed default, left is subtler (fully left = off), right is stronger. Includes a "Reset to default" button.
- The original audio is never reduced. The room is added on top, band-limited to keep bass and vocals clean, and still goes through the safety limiter.
- Switching spaces fades out and back in over a few milliseconds, so there are no clicks.
- Settings saved by 0.1.0 load unchanged. Unknown future values fall back to their defaults instead of resetting all settings.
- Device Status lists environments. As with spatial audio, they work only in the built-in player.

## 0.1.0
- First release: built-in player, modes, 10-band EQ, spatial 3D with head tracking, output profiles with safety limits, system-wide enhancement for apps that announce their sessions, headphone correction, hearing profile, and the other extras listed in the README.
