// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.application")
    id("dibs.android.compose")
    id("dibs.hilt")
}

android {
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    // The composition root: features see only domain interfaces; the app links
    // in :core:data so Hilt can bind the implementations (CLAUDE.md: ":app —
    // navigation host, DI wiring, nothing else").
    implementation(project(":core:data"))
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
