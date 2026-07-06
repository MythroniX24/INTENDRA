/*
 * INTERNDRA — Privacy-First AI OS
 * app/build.gradle.kts  v2.1.0
 *
 * Kotlin  1.9.22  (matches root build.gradle.kts)
 * AGP     8.7.3
 * KSP     1.9.22-1.0.17
 * CompileSDK / TargetSDK  35
 * MinSDK  26 (Android 8.0 — llama.cpp NDK requirement)
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace  = "com.interndra"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.interndra"
        minSdk          = 26          // Android 8.0 — required by llama.cpp JNI
        targetSdk       = 35
        versionCode     = 3
        versionName     = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Expose model filename as BuildConfig constant so code never has
        // hard-coded strings that can drift out of sync
        buildConfigField("String", "LOCAL_MODEL_FILENAME",
            "\"Qwen2.5-3B-Instruct-Q4_K_M.gguf\"")
        buildConfigField("String", "LOCAL_MODEL_URL",
            "\"https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf\"")
        buildConfigField("String", "OPENROUTER_DOMAIN",
            "\"openrouter.ai\"")

        // NOTE: no ndk{} / externalNativeBuild block here.
        // llama.cpp JNI (.so) is compiled separately and dropped into
        // app/src/main/jniLibs/<abi>/ as a pre-built shared library.
        // Build fails gracefully with a warning when .so is absent;
        // LocalAiEngine falls back to rule-based responses.
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Never embed API keys in release builds
            buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
        }
        debug {
            isDebuggable    = true
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            buildConfigField("String", "OPENROUTER_API_KEY",
                "\"${project.findProperty("OPENROUTER_API_KEY") ?: ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    composeOptions {
        // Must match Kotlin 1.9.22 — see https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }

    // Allow Room schema export for version-control and migration verification
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
    }
}

dependencies {
    // ── Compose BOM (pins all Compose library versions together) ──────────
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.foundation:foundation")

    // ── AndroidX core ─────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.startup:startup-runtime:1.2.0")

    // ── Room ──────────────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── DataStore (encrypted API key / preferences) ───────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── WorkManager (scheduled automations) ──────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ── Networking ────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── JSON ─────────────────────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.11.0")

    // ── HTML parsing (web search results) ────────────────────────────────
    implementation("org.jsoup:jsoup:1.18.1")

    // ── Markdown rendering ────────────────────────────────────────────────
    // Renders AI replies with code blocks, tables, bold, etc. in a TextView
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")

    // ── Coroutines ───────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // ── Splash Screen API ─────────────────────────────────────────────────
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ── ML Kit — on-device OCR (no data leaves device) ───────────────────
    // OcrPipeline.kt has a graceful no-op fallback if this is removed.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ── ML Kit — barcode / QR scanning (optional — comment out if unused) ──
    // implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ── Debug / testing ───────────────────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
