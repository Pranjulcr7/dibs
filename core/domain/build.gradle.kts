// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.kotlin.jvm")
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
