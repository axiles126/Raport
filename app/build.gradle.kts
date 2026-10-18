// Reconstructed from the APK (META-INF/*.version, AndroidManifest, app-metadata.properties:
// AGP 9.2.1, Kotlin metadata 2.2.0). Versions without a .version file are marked TODO(decomp).
plugins {
    id("com.android.application") version "9.2.1"
    // AGP 9 has built-in Kotlin; only the compiler plugins are applied.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" // TODO(decomp): exact Kotlin 2.2.x unknown
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

android {
    namespace = "com.example.report"
    compileSdk = 37

    defaultConfig {
        // Code namespace stays com.example.report: the sync protocol serializes class names
        // ("com.example.report.sync.Op.*"), so phones with the original build stay compatible.
        applicationId = "io.github.axiles126.report"
        minSdk = 29
        targetSdk = 37
        versionCode = 40
        versionName = "4.9"
    }

    signingConfigs {
        // Release key comes from CI secrets (RELEASE_KEYSTORE_B64 etc.) or local env; falls back to debug key.
        val ks = System.getenv("RELEASE_KEYSTORE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Original APK is signed with the author's key (CN=Report, O=Report, C=UA).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // AndroidX (exact versions from META-INF/*.version)
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // Compose (no BOM; explicit versions from META-INF)
    implementation("androidx.compose.ui:ui:1.9.2")
    implementation("androidx.compose.ui:ui-graphics:1.9.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.2")
    implementation("androidx.compose.foundation:foundation:1.9.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // XML theme parent Theme.Material3.DayNight.NoActionBar
    implementation("com.google.android.material:material:1.14.0")

    // Coroutines / serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // TODO(decomp): version not recorded

    // LAN sync: embedded hub server + client over WebSockets
    val ktor = "3.3.0" // TODO(decomp): version not recorded (Kotlin 2.2 / kotlinx-io era)
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    // slf4j-api, typesafe-config and kotlinx-coroutines-slf4j come in transitively (no slf4j binding in the APK).

    // Map + QR pairing
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // encrypted prefs (Keystore AES-256)
    implementation("org.osmdroid:osmdroid-android:6.1.20") // TODO(decomp): version not recorded
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // TODO(decomp): version not recorded
}
