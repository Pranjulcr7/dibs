// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
    id("dibs.hilt")
}

android {
    namespace = "app.dibs.core.ocr"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    // ML Kit stays inside this module (CLAUDE.md: concrete parser tiers never
    // leak into feature modules). Bundled model: fully on-device, no download.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
