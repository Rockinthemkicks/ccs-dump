plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ccssync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ccssync"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // On-device text recognition with the model bundled in the app (no download, works offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
