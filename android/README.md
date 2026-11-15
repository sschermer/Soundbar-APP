# Tonmaschine Android – native Kotlin/Compose

Independent Android companion for LG S95QR, built from the verified Windows reference on main. Android source is isolated in `android/`; no Windows code is modified.

## Install / build

- Download the current Android APK from GitHub Releases.
- Android 8+; no PC, external service or registration required.
- Build: JDK 17, Android SDK 36, Gradle 9.6; run `gradle :app:testDebugUnitTest :app:assembleDebug` from `android/`.
- GitHub Actions runs the tests, builds an APK and publishes a development prerelease for changes to main.
- Debug-signed GitHub Actions builds can use different temporary signing keys. Upgrading an already installed debug build may therefore fail with a signature mismatch. In that case, uninstall the old Android build first; this deletes **local Android profiles and configuration**, not NAS/Windows profiles. Android signing must be stabilized before official production releases.

## Profile storage (Android v0.2.0)

Open **IP / Einstellungen → Speicherort für Klangprofile**.

1. **Lokal** (default): Profiles remain private to this phone.
2. **NAS / Windows**: Enter the SMB directory of the Windows app's `Tonmaschine_Profile.json`, e.g. `\\\\NAS\\Klangprofile` or `smb://192.168.1.20/Klangprofile`. The JSON file must already exist in that directory.
3. Enter the NAS SMB username/password and optional domain. Leave username blank only for a server that explicitly permits anonymous access.
4. Tap **NAS verbinden** to save credentials and fetch the existing Windows profiles. Tap **Aktualisieren** when Windows has changed the file.

A direct SMB2/SMB3 connection is used (TCP 445; SMB1 disabled) through jCIFS-NG. Internet and local network permissions already required for the soundbar are used; no third-party cloud or service is involved. Passwords are stored using AES-GCM protected by the Android Keystore, rather than plain preferences.

NAS profile operations read the current data **under the same `.Tonmaschine_write_lock` directory lock** as Windows, write a temporary file, and rename it over the original only after the write finishes. If NAS connectivity, the lock, JSON validation, or rename fails, the original file must not be deliberately deleted or overwritten. Existing names require explicit confirmation before replacement, and deleting requires confirmation. NAS errors do not trigger silent local writes.

When NAS is offline the most recently retrieved profiles may still be displayed from an on-device read-only cache and applied to the soundbar. Saving/deleting is disabled until a fresh successful NAS read. Switching to local storage does not copy NAS profiles into local storage or vice versa.

The Windows app can use NAS paths that are mounted on the PC. Android needs SMB access to that same server over the local Wi-Fi. Make sure TCP 445 is reachable and that the account has read/write permission to the folder and can create and delete temporary files and the lock directory.

## Scope and limitations

- Direct encrypted Temescal TCP 9741 connection with automatic reconnect.
- Volume, input selection, mode selection, seven speaker levels, Night/DRC/Surround.
- Actual audio format reported in `s_audio_source`; no inferred channel counts.
- Native Dark Mode with explicit light-on-dark text colors.
- Locally stored or shared SMB profile configuration. Manual refresh for external Windows/NAS changes.
- JVM tests cover codec/framing, state reduction, profile import/serialization, simulated TCP soundbar, NAS path parsing, simulated NAS locking, updates and failures.
- SMB-to-a-real-NAS and LG S95QR compatibility cannot be established by CI tests alone; physically test before considering NAS writing fully verified.
- Security: use only on a trusted home LAN; neither the LG protocol fixed key nor NTLM authenticates an arbitrary soundbar. No telemetry.

## Licenses

This repository is MIT-licensed; the optional jCIFS-NG SMB library is independently licensed under LGPL-2.1. See `android/THIRD_PARTY_NOTICES.md` and the upstream source and license.
