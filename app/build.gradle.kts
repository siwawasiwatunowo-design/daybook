plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.example.daybook"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.daybook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val rawApiUrl = (project.findProperty("DAYBOOK_API_BASE_URL") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("DAYBOOK_API_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: "https://tubeless-gutless-unlined.ngrok-free.dev"
        val cleanApiUrl = rawApiUrl.trim().trimEnd('/')
        buildConfigField("String", "DAYBOOK_API_BASE_URL", "\"$cleanApiUrl\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")

    // Google Sign-In via Android Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-firestore")

    // Rich text editor for Compose
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc10")

    // OkHttp: used for the Whisper model download and the Gemini text-only call.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // sherpa-onnx (Whisper, on-device STT) is NOT a Maven dependency: its Kotlin API sources and
    // jniLibs are fetched by scripts/fetch-sherpa-onnx.sh (run it before gradle).

    // WorkManager: sequential STT worker (1 per recording, model loaded once).
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // EncryptedSharedPreferences: stores the user's own Gemini API key locally.
    // Key is NEVER hardcoded in the APK (see §9 of SYSTEM_FLOW.md).
    implementation("androidx.security:security-crypto:1.1.0")
}
