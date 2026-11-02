import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Address of the SmartEyeX server this build talks to by default (users can change it in the app).
// Set with  -PXNVA_API_BASE_URL=https://api.example.com  or the XNVA_API_BASE_URL environment variable.
// Empty is valid: self-hosters then enter their own server in the app. HTTPS is mandatory (plain HTTP only for the emulator).
val apiBaseUrl: String = ((project.findProperty("XNVA_API_BASE_URL") as String?) ?: System.getenv("XNVA_API_BASE_URL") ?: "").trim().trimEnd('/')
require(apiBaseUrl.isEmpty() || apiBaseUrl.startsWith("https://") || apiBaseUrl.startsWith("http://10.0.2.2")) {
    "XNVA_API_BASE_URL harus diawali https:// (http://10.0.2.2 hanya untuk emulator)"
}

android {
    namespace = "com.xnvalabs.smarteyex"
    // Google Play: new apps and updates must target API 36 (since Aug 31, 2026).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xnvalabs.smarteyex"
        // minSdk 31: ReminderRepository/DeviceScreen call
        // AlarmManager.canScheduleExactAlarms() (API 31+) directly.
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    // Release signing comes from environment variables (set as GitHub
    // Secrets in CI) — no keystore or password ever lives in the repo.
    // Without them, `assembleRelease` still builds, just unsigned.
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // CameraX — required by VisionScreen
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // JVM unit tests: protocol codec vs. reference vectors (src/test)
    testImplementation("junit:junit:4.13.2")
}
