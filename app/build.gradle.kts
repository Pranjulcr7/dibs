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
    implementation(project(":feature:groups"))
    implementation(project(":feature:expense"))
    implementation(project(":feature:settle"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
