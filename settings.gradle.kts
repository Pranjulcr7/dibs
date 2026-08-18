// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dibs"

include(":app")
include(":core:domain")
include(":core:data")
include(":core:ocr")
include(":core:sync")
include(":feature:groups")
include(":feature:expense")
include(":feature:settle")
include(":feature:scan")
include(":feature:settings")
