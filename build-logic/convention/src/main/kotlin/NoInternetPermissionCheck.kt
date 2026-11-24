// SPDX-License-Identifier: Apache-2.0
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure

/**
 * Fails the build if any variant's *merged* manifest declares the INTERNET
 * permission.
 *
 * Why: "no network" is CLAUDE.md invariant 3 and the first thing the README
 * promises. Our own manifests never asked for the permission — but a manifest
 * is merged from every dependency's, and ML Kit's transitive
 * `com.google.android.datatransport` (Google's telemetry uploader) brought
 * INTERNET into the `full` flavor unnoticed for months, while the docs kept
 * saying no build declared it. It was found by an end-to-end check on a
 * device, not by review. Without the permission the OS refuses every socket
 * the process tries to open, so this is the one check that makes the promise
 * true regardless of what any library intends.
 *
 * Opt-in relay sync (ROADMAP P9) will need a deliberate, documented exception
 * here — which is the point: adding network access must be a decision someone
 * makes, not something a dependency update does.
 */
abstract class NoInternetPermissionTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission[^>]*android:name="android\.permission\.INTERNET"""")
            .containsMatchIn(text)
        if (declared) {
            throw GradleException(
                "The merged manifest ${mergedManifest.get().asFile} declares android.permission.INTERNET.\n\n" +
                    "Dibs makes no network calls (CLAUDE.md invariant 3). This permission almost certainly " +
                    "arrived from a dependency's manifest; run `./gradlew :app:processFullDebugMainManifest` " +
                    "and read the manifest-merger blame report to see which one. app/src/main/AndroidManifest.xml " +
                    "removes it with tools:node=\"remove\" — do not delete that line.",
            )
        }
    }
}

internal fun Project.registerNoInternetPermissionCheck() {
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val name = variant.name.replaceFirstChar { it.uppercase() }
            val verify = tasks.register("verify${name}NoInternetPermission", NoInternetPermissionTask::class.java) {
                group = "verification"
                description = "Fails if the ${variant.name} merged manifest declares INTERNET (invariant 3)."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            }
            tasks.named("check").configure { dependsOn(verify) }
        }
    }
}
