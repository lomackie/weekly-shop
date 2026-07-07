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

    packaging {
        // onyxsdk-pen and its mmkv dependency both bundle libc++_shared.so
        jniLibs.pickFirsts += "lib/**/libc++_shared.so"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Low-latency e-ink pen rendering (see InkCanvasView); resolved from the
    // BOOX maven repo in settings.gradle.kts.
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")

    // The Pen SDK's raw input reader uses hidden platform APIs, blocked from
    // Android 11 unless exempted at startup (see App.onCreate).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
