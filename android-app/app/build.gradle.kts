plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.offlinechat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offlinechat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Optional single-ABI build, for handing the APK to a phone directly.
        // A full build bundles the WebRTC native library for four ABIs and comes out
        // around 54 MB, which is over Telegram's 50 MB bot upload limit.
        //   ./gradlew :app:assembleDebug -PabiFilter=arm64-v8a
        // With no -PabiFilter the build is unchanged and includes every ABI.
        (project.findProperty("abiFilter") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { abis -> ndk { abiFilters.addAll(abis) } }
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebRTC — Google's WebRTC, pre-built by Stream, published on Maven Central under org.webrtc.*
    implementation("io.getstream:stream-webrtc-android:1.3.9")

    // Embedded local signaling server (host role) — chhota, single-purpose WebSocket server
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Signaling client (peer role, aur host khud bhi apne server se isi se connect karta hai)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON (wire protocol) — web app ke JSON shape se match karta hai
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // E2EE — X25519 + HKDF-SHA256 (web app ke Web Crypto API ke saath byte-for-byte compatible)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
