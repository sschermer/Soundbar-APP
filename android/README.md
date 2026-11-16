# Tonmaschine Android – native Kotlin/Compose

Independent Android companion for LG S95QR, built from the verified Windows reference on main. Android source is isolated in `android/`; no Windows code is modified.

## Install / build

- Download the current Android APK from GitHub Releases.
- Android 8+; no PC, external service or registration required.
- Build: JDK 17, Android SDK 36, Gradle 9.6; run `gradle :app:testDebugUnitTest :app:assembleDebug` from `android/`.
- GitHub Actions runs the tests, builds an APK and publishes a development prerelease for changes to main.
- Since v0.3.1, installable Android releases are built using the **same persistent signing key** from the encrypted GitHub Actions repository secret `TONMASCHINE_ANDROID_SIGNING`. GitHub Actions will never publish a newly debug-signed update when the signing secret is missing.
- On the **first transition from v0.3.0 or earlier**, Android may require uninstalling the old debug-signed APK once. Prior to uninstalling, export your connection configuration as JSON/TXT. **Local sound profiles are not included in this export**; NAS profiles remain on the NAS. From v0.3.1 onward, updates install over the existing app without deleting its data, provided the original key is retained and `versionCode` keeps increasing.

## One-time GitHub Actions signing setup (v0.3.1)

The release key is generated once and **never stored in git**. The owner must add the signing bundle to an Actions repository secret:

1. Securely download and permanently back up the private `.p12` signing key and its generated secret bundle from the one-time setup.
2. Repository **Settings → Secrets and variables → Actions → New repository secret**.
3. Secret **Name**: `TONMASCHINE_ANDROID_SIGNING`; **Value**: the *entire* contents of the corresponding one-line generated text file.
4. Save the secret. Start **Actions → Android Tonmaschine APK → Run workflow → main** once.
5. After the build succeeds, download the stable release asset `Tonmaschine-Android-v0.3.1.apk` from GitHub Releases.

The workflow runs unit tests, builds a signed **release** APK, verifies its APK Signature Scheme v2 signature and compares the certificate SHA-256 against the public pin in `android/signing_certificate.sha256`. New versions must increment `versionCode` and continue to use exactly the same signing bundle. If the repository secret has not been entered, tests run but **no installable Android release is published**.

Keep the private signing key and the matching secret offline in a secure place. Never commit, upload to an issue, or share these files. Losing the private key can permanently prevent in-place app upgrades. GitHub does not automatically run a workflow when a secret is added, hence the one-time manual workflow launch.

## Profile storage (Android v0.3.0)

Open **IP / Einstellungen → Speicherort für Klangprofile**.

1. **Lokal** (default): Profiles remain private to this phone.
2. **NAS / Windows**: Enter the SMB directory of the Windows app's `Tonmaschine_Profile.json`, e.g. `\\\\NAS\\Klangprofile` or `smb://192.168.1.20/Klangprofile`. The JSON file must already exist in that directory.
3. Enter the NAS SMB username/password and optional domain. Leave username blank only for a server that explicitly permits anonymous access.
4. Tap **NAS verbinden** to save credentials and fetch the existing Windows profiles. Tap **Aktualisieren** when Windows has changed the file.

A direct SMB2/SMB3 connection is used (TCP 445; SMB1 disabled) through jCIFS-NG. Internet and local network permissions already required for the soundbar are used; no third-party cloud or service is involved. Passwords are stored using AES-GCM protected by the Android Keystore, rather than plain preferences.

NAS profile operations read the current data **under the same `.Tonmaschine_write_lock` directory lock** as Windows, write a temporary file, and rename it over the original only after the write finishes. If NAS connectivity, the lock, JSON validation, or rename fails, the original file must not be deliberately deleted or overwritten. Existing names require explicit confirmation before replacement, and deleting requires confirmation. NAS errors do not trigger silent local writes.

When NAS is offline the most recently retrieved profiles may still be displayed from an on-device read-only cache and applied to the soundbar. Saving/deleting is disabled until a fresh successful NAS read. Switching to local storage does not copy NAS profiles into local storage or vice versa.

The Windows app can use NAS paths that are mounted on the PC. Android needs SMB access to that same server over the local Wi-Fi. Make sure TCP 445 is reachable and that the account has read/write permission to the folder and can create and delete temporary files and the lock directory.

## Settings import/export (Android v0.3.0)

Open **IP / Einstellungen → Konfiguration sichern und wiederherstellen**.

- Export as `.json` or `.txt` using Android's system file picker. The backup contains the configured soundbar IP, NAS SMB folder, SMB username, domain, and the selected local/NAS profile location.
- Optional: tick **NAS-Passwort mit exportieren**. This exports the password **unencrypted** after an explicit warning and confirmation. Do not share the file or store it in an untrusted cloud location. Without that option the password is intentionally omitted and must be entered manually after import.
- Import via Android's document picker. Check the preview and confirm replacement of the IP/NAS configuration. The NAS password is immediately re-encrypted with the new device's Android Keystore; a new local soundbar connection and (if NAS is selected) an NAS read are initiated.
- Exported configuration does **not** include local sound profiles or the NAS profile contents. NAS profiles stay on the NAS and will be loaded through the restored settings. Local profiles remain unchanged.
- This is a plain text backup, not an encrypted archive. The file stays wherever the user saves it, not in the app's protected preferences.

## Scope and limitations

- Direct encrypted Temescal TCP 9741 connection with automatic reconnect.
- Volume, input selection, mode selection, seven speaker levels, Night/DRC/Surround.
- Actual audio format reported in `s_audio_source`; no inferred channel counts.
- Native Dark Mode with explicit light-on-dark content colors throughout all app surfaces (contrast correction in v0.2.1).
- Consistent input spacing and equal-height sound mode buttons. Volume control and scrolling unchanged.
- Locally stored or shared SMB profile configuration. Manual refresh for external Windows/NAS changes.
- JVM tests cover codec/framing, state reduction, profile import/serialization, simulated TCP soundbar, NAS path parsing, simulated NAS locking, updates and failures.
- SMB-to-a-real-NAS and LG S95QR compatibility cannot be established by CI tests alone; physically test before considering NAS writing fully verified.
- Security: use only on a trusted home LAN; neither the LG protocol fixed key nor NTLM authenticates an arbitrary soundbar. No telemetry.

## Licenses

This repository is MIT-licensed; the optional jCIFS-NG SMB library is independently licensed under LGPL-2.1. See `android/THIRD_PARTY_NOTICES.md` and the upstream source and license.
