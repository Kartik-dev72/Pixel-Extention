// AGP 9.x has built-in Kotlin, so no kotlin-android plugin here (same as the other extension modules).
plugins {
    id("com.android.application")
}

android {
    namespace = "com.theveloper.pixelplay.extension.spotify"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.theveloper.pixelplay.extension.spotify"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Provided by the host at runtime, must NOT be bundled into the extension APK.
    compileOnly(project(":extension-api"))

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
