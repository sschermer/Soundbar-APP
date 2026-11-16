plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.tonmaschine.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.tonmaschine.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The long-lived release key is injected from a GitHub Actions secret only.
    // No private key or password is ever committed to the repository.
    val signingKeystorePath = System.getenv("TONMASCHINE_KEYSTORE_FILE")
    if (!signingKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("persistentRelease") {
                storeFile = file(signingKeystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("TONMASCHINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TONMASCHINE_KEY_ALIAS")
                keyPassword = System.getenv("TONMASCHINE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("persistentRelease")
                isMinifyEnabled = false
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}


tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "passed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showStackTraces = true
    }
}
