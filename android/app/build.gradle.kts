plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weeklyshop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.weeklyshop"
        minSdk = 30 // Note Air 2 Plus runs Android 11
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Low-latency e-ink pen rendering (see InkCanvasView). Requires the BOOX
    // maven repo in settings.gradle.kts; check their GitHub for the latest:
    // implementation("com.onyx.android.sdk:onyxsdk-pen:1.4.11")
}
