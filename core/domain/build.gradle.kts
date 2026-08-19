// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

dependencies {
    // Pure-JVM only; verifyPureJvm enforces the boundary.
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.commons.codec)
    implementation(libs.eddsa)
}

// NFR-9: >90% line coverage in the domain layer, enforced by the build.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
