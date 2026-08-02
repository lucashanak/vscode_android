plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vscodetunnel.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vscodetunnel.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments("-DANDROID_STL=none") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // OSGi bundle metadata, meaningless on Android, and several multi-release jars ship
            // the same path under different version folders (jsch and bouncycastle collide on 15).
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:149.0.20260318190823")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // SSH client
    implementation("com.github.mwiede:jsch:0.2.21")
    // Ed25519 for jsch. jsch ships its JDK15+ EdDSA implementation under
    // META-INF/versions/15/, and D8 ignores multi-release overlays, so on Android only the
    // "requires Java15+" stub is packaged. Without this, ed25519 keys cannot be generated,
    // cannot authenticate, and ed25519 host keys cannot be verified — jsch falls back to its
    // BouncyCastle backend, which needs this on the classpath.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // WebSocket (for Cloudflare Tunnel SSH proxy)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Biometric auth
    implementation("androidx.biometric:biometric:1.1.0")
    // Keystore-backed encryption for stored SSH credentials
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Binary delta patching for updates
    implementation("io.sigpipe:jbsdiff:1.0")
}
