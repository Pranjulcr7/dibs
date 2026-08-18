// SPDX-License-Identifier: Apache-2.0
plugins {
    id("dibs.android.library")
}

android {
    namespace = "app.dibs.feature.expense"
}

dependencies {
    implementation(project(":core:domain"))
}
