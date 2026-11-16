# Security and privacy

Spatia doesn't collect, transmit or share any data. It has **no INTERNET permission**: the manifest removes it explicitly, along with ACCESS_NETWORK_STATE and WAKE_LOCK, in case a library tries to merge them in.

## OWASP MASVS mapping

| MASVS group | How Spatia addresses it |
|---|---|
| **STORAGE** | Settings (including the hearing profile) are kept in app-private DataStore. `allowBackup=false`, and the data-extraction rules exclude everything from cloud backup and device transfer. Logs never contain user data. Release builds strip `Log.v/d/i`. |
| **CRYPTO** | Not applicable: no secrets, keys or tokens exist. Per-device keys are a truncated SHA-256 of device type plus model name, never a hardware address. |
| **AUTH** | Not applicable: no accounts. |
| **NETWORK** | No network access at all. |
| **PLATFORM** | Exported components: the launcher activity; `PlaybackService`, as the MediaSession protocol requires; and a runtime-registered receiver that only accepts the two documented AudioEffect actions. Every intent extra is validated: session id > 0, package name checked against a regex, max 16 sessions. `SessionEffectService` isn't exported. Files are opened through the Storage Access Framework (scoped storage, no storage permission). The merged AndroidX `ProfileInstallReceiver` is protected by the system-only `DUMP` permission. Pending intents are immutable. |
| **CODE** | R8 full mode with resource shrinking. All untrusted input (EQ text files, WAV impulse responses, ReplayGain tags, stored JSON) goes through size-bounded, strict parsers with clamping, and the parser tests include malformed and oversized input. Dependencies are pinned to exact versions and verified with SHA-256 (`gradle/verification-metadata.xml`). Lint runs with `abortOnError`. No hardcoded secrets. |
| **RESILIENCE** | Out of scope for an offline app with no secrets. |
| **PRIVACY** | No analytics, ads, crash reporting or identifiers. Listening-dose tracking is opt-in and local. |

## Permissions

| Permission | Why |
|---|---|
| `MODIFY_AUDIO_SETTINGS` | Required to create audio effects on sessions and to set bit-perfect mixer attributes. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback in the built-in player. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | The opt-in system-wide enhancement service (Android 14+ requires a declared type). |
| `POST_NOTIFICATIONS` | Requested only when system-wide enhancement is turned on (Android 13+), to show its ongoing notification. |

## Reporting

Please report vulnerabilities privately to the repository owner rather than in public issues.
