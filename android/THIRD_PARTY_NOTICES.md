# Android third-party notices

Tonmaschine is MIT licensed (root `LICENSE`). The following runtime dependencies remain governed by their own licenses.

- **jCIFS-NG 2.1.10** – LGPL-2.1, © jCIFS contributors. Package: `eu.agno3.jcifs:jcifs-ng:2.1.10`. Upstream source: https://github.com/AgNO3/jcifs-ng . License: https://github.com/AgNO3/jcifs-ng/blob/master/LICENSE and https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html . This is an independently maintained third-party component; its LGPL conditions apply to the bundled library.
- **Bouncy Castle** – dependency of jCIFS-NG, governed by the Bouncy Castle License. https://www.bouncycastle.org/about/license/
- **SLF4J** – dependency of jCIFS-NG, MIT license. https://www.slf4j.org/license.html
- **AndroidX and Jetpack Compose** – Apache-2.0. https://developer.android.com/jetpack/androidx
- **Kotlin / kotlinx.coroutines** – Apache-2.0. https://github.com/Kotlin/kotlinx.coroutines
- **Temescal protocol behavior** – based on google/python-temescal and lg-bartalk (Apache-2.0). See root `THIRD_PARTY_NOTICES.md`.

The APK build does not modify jCIFS-NG source. This repository includes all app source and reproducible Gradle dependency coordinates so a recipient can rebuild the app with another compliant jCIFS-NG build. Do not assume the third-party code is MIT licensed.
