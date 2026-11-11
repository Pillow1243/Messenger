plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------------------------
// Backend configuration
//
// The Supabase URL and the *publishable* (anon) key are not secrets: they ship inside every
// client by design and are guarded by row level security on the server. They can still be
// overridden per machine / per CI run with -PsupabaseUrl=... -PsupabaseKey=... or the
// SUPABASE_URL / SUPABASE_PUBLISHABLE_KEY environment variables.
// A service-role key must never be placed here - see docs/SECURITY.md.
// ---------------------------------------------------------------------------------------------
val supabaseUrl: String =
    (findProperty("supabaseUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://lnhmbjqjfrnqkpsjpszb.supabase.co"

val supabaseKey: String =
    (findProperty("supabaseKey") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("SUPABASE_PUBLISHABLE_KEY")?.takeIf { it.isNotBlank() }
        ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxuaG1ianFqZnJucWtwc2pwc3piIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQxNjgzNjgsImV4cCI6MjEwOTc0NDM2OH0.BeN6fTmXfiWVCrISNYnwZuPNeqNJRRQucq-O1r4Zu90"

// Production signing is only configured when the owner supplies credentials through the
// environment (CI secrets). Otherwise the release build is left unsigned, and `-PdemoSign=true`
// produces a debug-signed demo APK that is clearly labelled as such.
val releaseKeystorePath: String? = System.getenv("MESSENGER_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword: String? = System.getenv("MESSENGER_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = System.getenv("MESSENGER_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = System.getenv("MESSENGER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean = releaseKeystorePath != null && releaseKeystorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null
val demoSign: Boolean = (findProperty("demoSign") as String?)?.toBoolean() ?: false

android {
    namespace = "com.offlinemesh.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offlinemesh.messenger"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // libsignal ships a large Rust JNI library per ABI. Only the two ABIs that real
        // Android 8+ phones use are packaged; x86/x86_64 are emulator-only and would
        // otherwise roughly double the download.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_API_KEY", "\"$supabaseKey\"")
        // Kept for the settings screen: the app now has a network path, but the mesh remains
        // fully functional with no connectivity at all.
        buildConfigField("boolean", "OFFLINE_ONLY", "false")
        buildConfigField("String", "BUILD_CHANNEL", "\"dev\"")
        buildConfigField("boolean", "PRODUCTION_SIGNED", hasReleaseSigning.toString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "BUILD_CHANNEL", "\"debug\"")
        }
        release {
            // R8 stays off until the app has been validated on physical devices with a shrunk
            // build: libsignal's JNI bridge resolves Java symbols by name at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                demoSign -> signingConfigs.getByName("debug")
                else -> null
            }
            buildConfigField("String", "BUILD_CHANNEL", if (hasReleaseSigning) "\"production\"" else "\"unsigned\"")
        }
        create("demo") {
            // Debug-signed, release-configured build used for the public demo download. It is
            // never published to the production release channel (see .github/workflows).
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BUILD_CHANNEL", "\"demo\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compress the native libraries inside the APK instead of storing them raw.
            useLegacyPackaging = true
            // libsignal's AAR also contains a JNI test build plus desktop libraries that
            // Android can never load. Excluding them removes ~270 MB.
            excludes += setOf(
                "**/libsignal_jni_testing.so",
                "**/libsignal_jni_testing_*.so",
                "libsignal_jni_testing_*",
                "**/*.dylib",
                "**/*.dll",
                "*.dylib",
                "*.dll",
                "**/libsignal_jni_testing_*"
            )
        }
        resources {
            excludes += setOf(
                "libsignal_jni_testing_amd64.dylib",
                "libsignal_jni_testing_aarch64.dylib",
                "libsignal_jni_amd64.dylib",
                "libsignal_jni_aarch64.dylib",
                "signal_jni_testing_amd64.dll",
                "signal_jni_testing_aarch64.dll",
                "signal_jni_amd64.dll",
                "signal_jni_aarch64.dll",
                "*.dylib",
                "*.dll",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        // Lint findings that would crash or mislead users must fail the build. Warnings are
        // reported but do not block a merge.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

ksp {
    // Exported Room schemas are committed so every migration can be reviewed and regression
    // tested against the previous version.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Storage: Room over SQLCipher (whole DB encrypted at rest).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("net.zetetic:sqlcipher-android:4.5.7@aar")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Transport 1: Google Nearby Connections (BLE + Wi-Fi Direct, no internet needed).
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // Transport 2: HTTPS + WebSocket to the Supabase backend. OkHttp is used directly instead
    // of a larger client SDK so the online path has no dependency on the mesh path and vice
    // versa, and so every request/response is explicit and auditable.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Cryptography: official Signal Protocol implementation (PQXDH + Double Ratchet).
    implementation("org.signal:libsignal-android:0.86.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR codes (display + scan) for out-of-band contact exchange.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
