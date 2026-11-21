# Security and privacy (Windows)

Spatia follows Microsoft Security Development Lifecycle (SDL) practices for desktop apps. Each control below is checked automatically in CI (`.github/workflows/windows.yml`), and a failure blocks the build and the release.

| Control | How | Checked by |
|---|---|---|
| **No network, no telemetry** | The app never opens a network connection; nothing leaves the PC. "Download Equalizer APO" only opens the official page in your browser. | Code review; no networking APIs used |
| **Least privilege** | Runs as a normal user (`asInvoker` manifest). The installer is per-user (`PrivilegesRequired=lowest`), so there's no elevation, and it installs no drivers or services. Elevation is used for exactly one thing: **Connect** (and Disconnect) to Equalizer APO, see below. | Manifest + Inno Setup script |
| **Equalizer APO connect** | One UAC prompt starts `Spatia.exe --apo-connect <your SID>`, which handles the command before any UI and exits. It creates `config\Spatia`, grants **Modify on that folder only, to your account's SID only** (validated with `SecurityIdentifier`), backs up `config.txt` once to `config.txt.spatia-backup`, and appends one `Include: Spatia\spatia.txt` line (idempotent; other lines are kept byte for byte). Afterwards the normal, unelevated app writes only inside `config\Spatia`. Disconnect removes the line; uninstall disconnects too (or, without admin rights, switches Spatia's file to "no processing"). | Unit tests on a temp folder + UI test (connect, disconnect, uninstall) |
| **Binary hardening** | ASLR (DYNAMIC_BASE, HIGH_ENTROPY_VA), DEP (NX_COMPAT), no writable+executable sections, relocations present. | **BinSkim** (Microsoft's binary analyzer, pinned version + SHA-256) on Spatia.exe, the app assemblies and spatia_dsp.dll; SARIF report uploaded |
| **DLL hijacking protection** (CWE-427) | Native libraries load only from the app folder or System32 (`DefaultDllImportSearchPaths`). | Assembly attributes in Spatia.Core/Audio/UI |
| **Vulnerable dependencies** | All NuGet packages pinned (`Directory.Packages.props`) and restored in locked mode (`packages.lock.json`). | `dotnet list package --vulnerable --include-transitive`; the Kotlin side uses Gradle dependency verification (SHA-256) |
| **Static analysis** | All .NET security rules (`AnalysisModeSecurity=All`) plus the recommended rule set, warnings as errors. | Every build |
| **Malware scan** | The packaged app is scanned with Microsoft Defender. | `MpCmdRun` on the CI runner (when available) |
| **Untrusted input** | Settings JSON, EQ text files and WAV impulse responses are size-checked and parsed by bounded parsers; `config.txt` is size-checked; generated Equalizer APO text uses fixed, culture-invariant formats and validated file names. The engine's C API never lets an exception cross the boundary. Strings load with DTD processing disabled (XXE-safe). | Unit tests (hostile inputs) |
| **Data at rest** | Settings: `%LOCALAPPDATA%\Spatia\settings.json` (and an imported `impulse.wav`), validated on load and written atomically. Equalizer APO files (`config\Spatia\spatia.txt`, `env_*.wav`) are written atomically; stale ones are deleted. Diagnostic log: `%LOCALAPPDATA%\Spatia\logs\spatia.log` (versions, startup steps, errors; never file names, device names or settings). | Unit tests |
| **Fail visibly** | Startup failures are logged and shown in a Windows dialog, then the app exits. It never keeps running invisibly. | UI test, including a missing-engine run |
| **Supply chain (CI)** | GitHub Actions pinned by commit SHA; least-privilege tokens (`contents: read`, `write` only for the release job). | Workflow files |

## Exceptions (with reasons)

- **Control Flow Guard (BinSkim BA2008)** is disabled for `spatia_dsp.dll` only. Kotlin/Native builds it with a MinGW-based toolchain that can't emit CFG metadata. The DLL still has ASLR, DEP and no writable+executable sections, and it exposes only bounded, validated C functions. See `security/binskim-config.xml`.

## Code signing

Builds aren't Authenticode-signed yet, so Windows SmartScreen warns on first run. To enable signing, the repository owner adds two secrets:

- `SPATIA_SIGN_PFX_BASE64`: the code-signing certificate (.pfx), base64-encoded
- `SPATIA_SIGN_PASSWORD`: its password

CI then signs `Spatia.exe`, `spatia_dsp.dll` and the installer with SHA-256 and an RFC 3161 timestamp. No certificate is ever stored in the repository.

## Reporting

Please report vulnerabilities privately to the repository owner rather than in public issues.
