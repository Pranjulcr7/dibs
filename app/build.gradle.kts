// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.application")
    id("dibs.android.compose")
    id("dibs.hilt")
}

android {
    packaging {
        // BouncyCastle and Tink both ship this OSGi manifest; neither is read at runtime.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // BouncyCastle post-quantum resources (~1.3 MB); Dibs uses only Argon2.
        resources.excludes += "org/bouncycastle/pqc/**"
    }
    namespace = "app.dibs"

    defaultConfig {
        applicationId = "app.dibs"
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Checked-in dummy keystore so a clean clone builds with zero setup.
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // OPEN_SOURCE_SETUP §7: the F-Droid build must be fully FOSS. ML Kit is the
    // only non-FOSS dependency, so the flavors differ in exactly one thing —
    // which ReceiptParser the DI graph binds. Same applicationId: F-Droid signs
    // its own builds, and the two flavors are never co-installed.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
    }

    buildTypes {
        release {
            // NFR-6 (< 20 MB): R8 + resource shrinking; ML Kit and SQLCipher
            // native code dominate what remains, so per-ABI size is the
            // honest number (Play delivers split APKs from the bundle).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    // The composition root: features see only domain interfaces; the app links
    // in :core:data so Hilt can bind the implementations (CLAUDE.md: ":app —
    // navigation host, DI wiring, nothing else").
    implementation(project(":core:data"))
    // ML Kit rides only in the Play-style flavor; foss binds NoOpReceiptParser
    // (see app/src/foss/… FossReceiptModule) and drops ~11 MB of bundled model.
    "fullImplementation"(project(":core:ocr"))
    implementation(project(":core:ui"))
    implementation(project(":feature:groups"))
    implementation(project(":feature:expense"))
    implementation(project(":feature:settle"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
