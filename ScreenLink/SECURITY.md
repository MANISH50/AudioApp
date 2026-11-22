# ScreenLink security

ScreenLink sends your phone's screen and keystrokes over your local network, so it is built to make sure only a phone
*you* paired can show its screen or type on your laptop, and that nobody else on the network can watch or inject.

## Connection

- **Transport:** TCP on the local network, always inside **TLS 1.3** (TLS 1.2 on Windows 10, whose TLS stack has no 1.3
  server). No plain-text traffic; Android's network security config forbids cleartext for the whole app.
- **Mutual authentication:** each device has its own key pair.
  - Phone: EC P-256 key generated inside **Android Keystore** (hardware-backed where available). The private key can't be
    exported, read or backed up.
  - Laptop: EC P-256 key with a self-signed certificate, stored in `%LOCALAPPDATA%\ScreenLink\identity.bin`, encrypted
    with **Windows DPAPI** (current user).
- **Pairing (numeric comparison):** on first contact both devices show a 6-digit code computed from *both* certificate
  fingerprints (SHA-256). The user must confirm on the phone **and** click *Allow* on the laptop. An attacker in the
  middle would have to substitute certificates, which changes the code. Each side then **pins** the other's fingerprint;
  later connections without the pinned certificate are refused (the phone shows "identity changed" and stops).
- Until pairing completes, nothing except the greeting and the pairing messages is processed: no video, no keys.
- Laptop: one pairing prompt at a time, at most 5 per minute, 60-second timeout. Handshakes time out after 10 s;
  idle connections after 20 s.

## Input safety (laptop)

- Keystrokes are accepted only from the connected, paired phone, and only while *Allow typing from the phone* is on.
- Keys are limited to an allow-list: letters, digits, F1–F12, navigation and editing keys, with Ctrl/Alt/Shift/Win as
  modifiers. Control characters in text are dropped. Each shortcut presses and releases its modifiers in one batch, so
  no key is left held down if the connection drops.
- ScreenLink runs as a normal user (`asInvoker`); it can't type into elevated (administrator) windows.

## Laptop controlling the phone (0.2)

- **Off by default.** It needs two deliberate steps on the phone: switching on the *ScreenLink control* accessibility
  service in Android settings, and *Let the laptop control this phone* in the app (shown with a full disclosure of what
  it allows).
- Control messages are acted on only while all of these hold: connected to the **paired** (pinned) laptop, casting the
  **entire** screen (not a single app), the in-app switch on, and the service on. Otherwise they are dropped. The phone
  tells the laptop whether control is available; the casting notification says when the laptop can control the phone.
- What the laptop can do: taps, long presses, swipes, scrolls, Back/Home/Recents/Notifications, and inserting text into
  the focused text field. It can't read anything back: no content leaves the phone except the cast picture and a 1-byte
  "ready" flag. The service receives no accessibility events, and it never edits password fields.
- Messages are strictly parsed (fixed sizes, valid codes, UTF-8 ≤ 1 KiB); gestures are bounded (≤ 400 points, ≤ 10 s).
- Threat model: a paired laptop (or someone at it) can operate the phone while you cast with control on, just as if
  they held it. Don't enable control for laptops you don't trust; turn the switch off or *Forget* the laptop to revoke.
- Google Play requires an accessibility declaration for this; sideloaded installs are unaffected.

## Robustness

- Every message has a length limit (4 MiB frame, 1 KiB text, 128-byte names); malformed input closes the connection.
- Protocol parsers are unit-tested on both platforms against the same byte layouts and test vectors.
- Discovery (UDP 47421) only ever returns a fixed public reply (port, certificate fingerprint, computer name) and is
  rate-limited. It is a hint for the phone's list; trust still comes from TLS + pairing.

## Android standards

- Accessibility service: `BIND_ACCESSIBILITY_SERVICE`-protected (only the system can bind it), user-enabled only.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`,
  `POST_NOTIFICATIONS`. No storage, location, microphone or camera; the manifest actively removes them if a library tries
  to add them.
- Screen capture follows the Android 14+ rules: user consent through the system dialog on **every** session, a foreground
  service of type `mediaProjection` started *before* the consent token is used, an ongoing notification with a Stop
  button, and capture ends when the user stops it from the status bar or the connection ends. Windows marked
  `FLAG_SECURE` by other apps are never captured (Android blacks them out).
- `allowBackup=false` and data-extraction rules exclude everything. Only the launcher activity is exported.
- Release builds are minified with R8 and strip debug logging. `lint` runs with `abortOnError`.

## Windows standards

- Per-user install, no administrator rights, no services or drivers. The optional firewall rule (private networks
  only, ScreenLink.exe only) is added from inside the app with an explicit UAC prompt, and removed on uninstall.
- All .NET security analyzers enabled with warnings as errors; locked, pinned NuGet dependencies; CI checks for
  vulnerable packages, runs Microsoft BinSkim on the binaries and a Defender scan on the package.
- Native DLLs load only from System32 (DLL-hijacking protection).
- Code signing: CI signs automatically when the repository has `SCREENLINK_SIGN_PFX_BASE64` and
  `SCREENLINK_SIGN_PASSWORD` secrets. Without a certificate, SmartScreen warns on first run.

## Not in scope (v1)

- Audio casting, controlling the phone from the laptop, and connections across the internet are not supported.
