// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Convention for Android library modules (`:core:*` except domain, `:feature:*`). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                compileSdk = versionFromCatalog(target, "compileSdk").toInt()
                defaultConfig {
                    minSdk = versionFromCatalog(target, "minSdk").toInt()
                }
                configureAndroidCommon(target)
            }
        }
    }
}
