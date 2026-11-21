# Security and privacy (Windows)

- **No network access in the app and no telemetry.** Nothing leaves the PC.
- **Runs as a normal user** (`asInvoker` manifest). No drivers or services are installed, and no administrator rights are needed.
- **Settings** live in `%LOCALAPPDATA%\Spatia\settings.json`. Loading is size-bounded (512 KB). Corrupt files fall back to defaults. Every value is re-validated by the shared engine. Device keys must be 16 hex characters (SHA-256 of type + name, never a hardware ID). Writes are atomic.
- **Untrusted files** (EQ text, WAV impulse responses, audio files) are size-checked before reading, then parsed by the engine's bounded parsers. The engine's C API never lets an exception cross the boundary, and the C# side treats every non-zero return as an error.
- **Strings** load from embedded `.resw` files with DTD processing disabled (XXE-safe).
- **Dependencies** are pinned centrally (`Directory.Packages.props`), and restore runs in locked mode (`packages.lock.json`) in CI. GitHub Actions are pinned by commit SHA. The Kotlin side uses Gradle dependency verification (SHA-256).
- **Analyzers** run with warnings as errors.
- **Not yet:** code signing. The zip is unsigned until a signing certificate is provided, so SmartScreen will warn on first run.
