// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun versionCatalog(project: Project): VersionCatalog =
    project.extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun versionFromCatalog(project: Project, alias: String): String =
    versionCatalog(project).findVersion(alias).get().requiredVersion

internal fun CommonExtension<*, *, *, *, *, *>.configureAndroidCommon(project: Project) {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    project.tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
