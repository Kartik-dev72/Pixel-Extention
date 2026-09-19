plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.theveloper.pixelplay.extension.youtube"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.theveloper.pixelplay.extension.youtube"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // compileOnly, not implementation — see MusicExtension's KDoc in :extension-api. This must
    // never end up inside this extension's own APK/dex, or the host's DexClassLoader cast to
    // MusicExtension will fail at runtime even though this compiles fine.
    compileOnly(project(":extension-api"))

    // The host app no longer bundles NewPipeExtractor; this extension is the only place it lives.
    // YouTube's client fingerprinting is version-sensitive, so bump this when playback breaks.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:0.26.5")
    implementation(libs.okhttp)
    implementation(libs.timber)
}
