// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
    id("dibs.android.compose")
    id("dibs.hilt")
}

android {
    namespace = "app.dibs.feature.scan"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Invite QR codes are decoded on-device with zxing (pure Java) — no ML Kit
    // barcode dependency, so the foss flavor can scan too.
    implementation(libs.zxing.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
