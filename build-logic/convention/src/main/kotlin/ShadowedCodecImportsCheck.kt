// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Fails the build on imports of commons-codec classes that Android's boot
 * classpath shadows.
 *
 * Why: `/system/framework/org.apache.http.legacy.jar` ships an ancient
 * `org.apache.commons.codec` that wins over any bundled version on real
 * devices. Its pre-1.4 API crashed op-id computation with `NoSuchMethodError`
 * on hardware while every JVM test passed (caught by the on-device encrypted
 * database test; DECISIONS.md D19). This check turns that hard-won lesson into
 * a build failure instead of a memory.
 *
 * `digest.Blake3` is deliberately NOT banned: the framework jar has no copy of
 * it to shadow, which the on-device test verifies.
 */
internal fun Project.registerShadowedCodecImportsCheck() {
    val bannedPrefixes = listOf(
        "import org.apache.commons.codec.binary.",
        "import org.apache.commons.codec.digest.DigestUtils",
        "import org.apache.commons.codec.language.",
        "import org.apache.commons.codec.net.",
    )
    val verify = tasks.register("verifyNoShadowedCodecImports") {
        group = "verification"
        description =
            "Fails on imports of commons-codec classes that Android's boot classpath shadows (DECISIONS.md D19)."
        val srcDir = layout.projectDirectory.dir("src")
        doLast {
            val violations = mutableListOf<String>()
            srcDir.asFileTree.matching { include("**/*.kt") }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (bannedPrefixes.any { trimmed.startsWith(it) }) {
                        violations += "${file.relativeTo(projectDir)}:${index + 1}: $trimmed"
                    }
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException(
                    "Shadowed commons-codec import(s) found:\n" +
                        violations.joinToString("\n") { "  - $it" } +
                        "\n\nAndroid's boot classpath ships an ancient org.apache.commons.codec inside " +
                        "/system/framework/org.apache.http.legacy.jar. On real devices it shadows the " +
                        "bundled jar, so these classes throw NoSuchMethodError at runtime while every JVM " +
                        "test passes — this broke op-id computation on hardware once already " +
                        "(DECISIONS.md D19). Hand-roll the utility or use a library that does not share " +
                        "the org.apache.commons.codec package.",
                )
            }
        }
    }
    tasks.named("check").configure { dependsOn(verify) }
}
