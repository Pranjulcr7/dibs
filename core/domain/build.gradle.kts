// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.kotlin.jvm")
    alias(libs.plugins.kover)
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
