// SPDX-License-Identifier: Apache-2.0
plugins {
    `kotlin-dsl`
}

group = "app.dibs.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "dibs.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
        register("androidLibrary") {
            id = "dibs.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "dibs.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "dibs.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = "dibs.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
