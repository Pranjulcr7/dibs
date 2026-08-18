// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
    id("dibs.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.dibs.core.data"

    defaultConfig {
        // Room schema JSONs are exported here and committed; the migration test
        // harness reads them as test assets (CLAUDE.md invariant 5).
        testOptions.unitTests.isIncludeAndroidResources = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotest.assertions.core)

    // On-device tests: the SQLCipher + Keystore path Robolectric cannot exercise.
    androidTestImplementation(project(":core:domain"))
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
