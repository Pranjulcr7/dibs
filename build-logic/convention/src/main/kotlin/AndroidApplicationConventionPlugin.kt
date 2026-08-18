// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Convention for the single `:app` module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")

            extensions.configure<ApplicationExtension> {
                compileSdk = versionFromCatalog(target, "compileSdk").toInt()
                defaultConfig {
                    minSdk = versionFromCatalog(target, "minSdk").toInt()
                    targetSdk = versionFromCatalog(target, "targetSdk").toInt()
                }
                configureAndroidCommon(target)
            }
        }
    }
}
