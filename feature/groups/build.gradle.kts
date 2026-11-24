// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
    id("dibs.android.compose")
    id("dibs.hilt")
}

android {
    namespace = "app.dibs.feature.groups"

    defaultConfig {
        testOptions.unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    // QR rendering for invite links (M6); pure Java, present in both flavors.
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
