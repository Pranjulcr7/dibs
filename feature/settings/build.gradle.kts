// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
}

android {
    namespace = "app.dibs.feature.settings"
}

dependencies {
    implementation(project(":core:domain"))
}
