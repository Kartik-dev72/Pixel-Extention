plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.theveloper.pixelplay.extension.api"
    compileSdk = 37

    defaultConfig {
        // Matches :app's minSdk — extensions targeting this SDK run on the same OS versions
        // PixelPlay itself supports.
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // Deliberately nothing else. This module must stay dependency-free: every extension APK
    // compiles against it, and every added dependency here is a version an extension author
    // would have to match. Kotlin stdlib is the only thing pulled in implicitly.
}
