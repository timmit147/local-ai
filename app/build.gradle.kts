plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.pocketai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pocketai"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Required for background model downloads and notifications
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Offline image recognition (model is bundled in the APK, no internet needed)
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Existing local AI inference library
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
