# ScreenLink

Show your Android phone's screen on your Windows laptop, and use the phone as a keyboard for the laptop.

| Folder | What | Built with |
|---|---|---|
| [`android/`](android/) | Phone app: casts the screen, keyboard screen | Kotlin, Jetpack Compose, MediaProjection + MediaCodec (H.264) |
| [`windows/`](windows/) | Laptop app: shows the phone screen, types what the phone sends | C#, .NET 10, WinUI 3 (Windows App SDK) |

## What you need

- **Phone:** Android 10 or newer.
- **Laptop:** Windows 10 version 2004 or newer, or Windows 11 (x64).
- **Both on the same Wi-Fi**, and Windows must treat that Wi-Fi as a **private** network (Settings → Network & internet → Wi-Fi → your network → *Private network*). Guest Wi-Fi and some office networks block devices from talking to each other.
- Nothing else: no cable, no developer mode, no cloud account. Nothing leaves your network.

## Install

Download both files from the [**Releases page**](https://github.com/MANISH50/AudioApp/releases) (latest `ScreenLink …` release):

| File | Where | How |
|---|---|---|
| `ScreenLink-<version>.apk` | Phone | Open the download on the phone; allow *Install unknown apps* for your browser/Files app when Android asks. |
| `ScreenLink-Setup-<version>-x64.exe` | Laptop | Run it. Installs for your user only, no administrator rights. SmartScreen may warn because it isn't code-signed yet: *More info → Run anyway*. |

The first time ScreenLink starts on the laptop, Windows Firewall may ask whether to allow it: choose **Private networks**.
If it doesn't ask and the phone can't find the laptop, click *Allow ScreenLink on private networks* in the app.

The APK on the Releases page is signed with a development key, which is fine for your own phones. To publish on Google
Play, sign a release build with your own keystore (`./gradlew :app:assembleRelease`, then `apksigner`).

New releases are made by pushing a tag `screenlink-v<version>`: CI builds, tests and attaches both files.

## Use

1. Open ScreenLink on the laptop. It shows its address and waits.
2. Open ScreenLink on the phone → **Laptop** tab → **Search**, tap your laptop → **Connect** (or type the address shown on the laptop).
3. **First time only:** both screens show a 6-digit code. If they match, tap *Codes match* on the phone and click *Allow* on the laptop. From then on they connect without asking.
4. **Cast** tab → *Start casting* → Android asks for permission → the phone's screen appears on the laptop. *F11* for full screen. Stop from the app or the notification.
5. **Keyboard** tab → type with the phone's own keyboard; the text appears wherever the laptop's cursor is. Extra keys (Esc, Tab, arrows, F1–F12), modifiers (Ctrl, Alt, Shift, Win) and shortcuts (copy, paste, undo, switch app) are below the text box.

### Good to know

- Android asks for permission every time you start casting; that's an Android rule for every screen-casting app.
- Apps that protect their content (banking apps, Netflix and similar) show as black. That's Android's protection, not a bug.
- Sound stays on the phone.
- Windows doesn't let any app type into programs running *as administrator* unless the typing app is also running as administrator.
- The laptop's *Allow typing from the phone* switch turns the keyboard off at any time. *Remove* a phone under *Paired phones* to revoke it.

## Troubleshooting

| Problem | Fix |
|---|---|
| Phone finds no laptop | Same Wi-Fi? Network set to *Private* on Windows? Click *Allow ScreenLink on private networks*. Or type the laptop's address. |
| "Couldn't reach the laptop" | ScreenLink must be open on the laptop. VPNs on either device can block local connections. |
| "This laptop's identity has changed" | ScreenLink was reinstalled on the laptop (or someone is impersonating it). *Forget* the laptop on the phone and pair again. |
| Picture stutters | Choose *Standard (720p)* quality, move closer to the router, or use 5 GHz Wi-Fi. |
| Laptop log | `%LOCALAPPDATA%\ScreenLink\logs\screenlink.log` (contains no typed text or screen content). |

## Build

```
cd ScreenLink/android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease   # JDK 17+, Android SDK 35
cd ScreenLink/windows && dotnet test tests/ScreenLink.Core.Tests                                 # .NET SDK 10, any OS
dotnet publish src/ScreenLink.App -c Release -o ../../out/ScreenLink                             # on Windows
```

CI (`.github/workflows/screenlink-android.yml`, `screenlink-windows.yml`) runs all of this plus BinSkim, a Defender scan, the
installer build and an install → launch → uninstall UI test on a real Windows machine.

See [`SECURITY.md`](SECURITY.md) for the security design and [`PROTOCOL.md`](PROTOCOL.md) for the wire protocol.
