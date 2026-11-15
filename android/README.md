# Tonmaschine Android (native)

Android application built with Kotlin and Jetpack Compose. Independent of the existing Python Windows controller. Source of truth: Windows main commit `bfb275d9e9b9f68d9e5a795438bdf1a6d01e220b`.

## Build

Run `gradle :app:testDebugUnitTest :app:assembleDebug` with JDK 17, Gradle 9.6, Android SDK 37.

GitHub Actions builds on Android changes and uploads `Tonmaschine-Android-debug`. When merged to main, the successful build also creates a prerelease with a downloadable debug-signed APK. Installing the APK requires temporarily allowing installations from your browser/file manager.

## Scope and limitations

- Verified against source: Temescal AES-256-CBC framing, port 9741, command names and keys, seven speaker levels, supported mode/input codes.
- Automatic reconnection; asynchronous unsolicited device messages; six-second signal refresh only while device is awake.
- Source selection only after the soundbar advertises its input list. eARC observed from the device is included if omitted from that list.
- Audio format shown verbatim from `s_audio_source`. No invented 2.0/5.1/7.1 claims.
- Android profiles are stored locally in SharedPreferences using compatible field names. There is no NAS access or Windows profile import interface.
- This build has **not** been verified with a physical LG S95QR. Input switching, all seven speaker levels and device-specific sound mode behavior need a live test.
- Requires Android 8 or newer. On Android 17 local-network permission is requested on connect.
- No account, analytics, backend or telemetric network requests.

## Security

The key and IV are static protocol constants inherited from open-source lg-bartalk. They do not authenticate the soundbar. Do not open TCP 9741 to the Internet; use only on trusted WLANs.

Portions of the Temescal wire behavior are based on the Apache-2.0 licensed python-temescal project and lg-bartalk. See the original repository's third-party notices.
