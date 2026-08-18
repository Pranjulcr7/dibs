// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Convention for pure Kotlin/JVM modules (`:core:domain`).
 *
 * Registers a `verifyPureJvm` task, wired into `check`, that fails the build if the
 * module ever gains an Android dependency, a project dependency, or an `android.*` /
 * `androidx.*` import. This enforces CLAUDE.md invariant 4 as a build check rather
 * than a convention.
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            }

            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                maxHeapSize = "2g"
            }

            val verifyPureJvm = tasks.register("verifyPureJvm") {
                group = "verification"
                description =
                    "Fails if this module depends on any Android artifact, any other project, " +
                        "or contains android/androidx imports."
                val srcDir = layout.projectDirectory.dir("src")
                val modulePath = target.path
                doLast {
                    val violations = mutableListOf<String>()

                    val forbiddenGroups = listOf(
                        "androidx.",
                        "com.android",
                        "com.google.android",
                        "com.google.firebase",
                        "com.google.mlkit",
                        "org.robolectric",
                    )
                    fun isForbidden(group: String) =
                        forbiddenGroups.any { group == it.trimEnd('.') || group.startsWith(it) }

                    listOf("compileClasspath", "runtimeClasspath", "testRuntimeClasspath").forEach { name ->
                        val configuration = configurations.findByName(name) ?: return@forEach
                        val resolutionResult = configuration.incoming.resolutionResult
                        resolutionResult.allComponents.forEach { component ->
                            when (val id = component.id) {
                                is ModuleComponentIdentifier -> {
                                    if (isForbidden(id.group)) {
                                        violations += "$name resolves Android artifact ${id.group}:${id.module}"
                                    }
                                }
                                is ProjectComponentIdentifier -> {
                                    if (id.projectPath != modulePath) {
                                        violations += "$name depends on project ${id.projectPath}; " +
                                            "this module must sit at the bottom of the dependency graph"
                                    }
                                }
                            }
                        }
                        // Requested-but-unresolved deps (an AAR fails variant matching on a JVM
                        // module before it ever reaches allComponents) must be caught too.
                        resolutionResult.allDependencies.forEach { dependency ->
                            val requested = dependency.requested
                            if (requested is ModuleComponentSelector && isForbidden(requested.group)) {
                                violations += "$name requests Android artifact ${requested.group}:${requested.module}"
                            }
                        }
                    }

                    srcDir.asFileTree.matching { include("**/*.kt") }.forEach { file ->
                        file.readLines().forEachIndexed { index, line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                                violations += "${file.relativeTo(projectDir)}:${index + 1} imports Android: $trimmed"
                            }
                        }
                    }

                    if (violations.isNotEmpty()) {
                        throw GradleException(
                            "Pure-JVM check failed for $modulePath (CLAUDE.md invariant 4):\n" +
                                violations.joinToString("\n") { "  - $it" },
                        )
                    }
                }
            }

            tasks.named("check").configure { dependsOn(verifyPureJvm) }
        }
    }
}
