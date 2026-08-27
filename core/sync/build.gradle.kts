// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.dibs.core.sync"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Same primitives the backup codec uses: XChaCha20-Poly1305 (Tink subtle)
    // and BLAKE3 (commons-codec). Ed25519 comes from :core:domain's Identity.
    implementation(libs.tink)
    implementation(libs.commons.codec)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
