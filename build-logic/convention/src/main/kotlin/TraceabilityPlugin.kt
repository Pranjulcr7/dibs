// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * `verifyTraceability`: keeps `docs/requirements.yaml` honest.
 *
 * Why: SPEC.md numbers every requirement, edge case, and user flow, and "is
 * that actually tested?" used to be answered by a hand-maintained table that
 * went stale within a milestone (AUDIT.md). The registry names, for each id,
 * the tests that cover it; this check fails the build when a named test file
 * is gone, when the quoted test name no longer appears in it, when an id is
 * missing or duplicated, or when a status claims more than its entry shows.
 * A renamed or deleted test can therefore never leave a requirement silently
 * uncovered while the registry still says "covered".
 *
 * The file is a deliberately tiny YAML subset (a flat list, fixed keys), read
 * by the parser below so the build needs no YAML dependency.
 */
class TraceabilityPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("lifecycle-base")
        val registry = layout.projectDirectory.file("docs/requirements.yaml")
        val root = layout.projectDirectory.asFile
        val verify = tasks.register("verifyTraceability") {
            group = "verification"
            description = "Checks docs/requirements.yaml: every id present once, every named test exists."
            inputs.file(registry)
            inputs.files(
                fileTree(root) {
                    include("**/src/test/**/*.kt", "**/src/androidTest/**/*.kt", "**/src/*Test/**/*.kt")
                    exclude("**/build/**")
                },
            )
            doLast {
                val entries = Traceability.parse(registry.asFile.readLines())
                val problems = Traceability.problems(entries, root)
                if (problems.isNotEmpty()) {
                    throw GradleException(
                        "docs/requirements.yaml has ${problems.size} problem(s):\n" +
                            problems.joinToString("\n") { "  - $it" } +
                            "\n\nEach requirement lists the tests that cover it as " +
                            "`<file> :: <text from the test name>`. If you renamed or removed a test, " +
                            "update its entry; if a requirement lost its coverage, change its status " +
                            "and say why in the note. Do not delete the entry.",
                    )
                }
                val counts = entries.groupingBy { it.status }.eachCount().toSortedMap()
                logger.lifecycle("Traceability: ${entries.size} ids — " + counts.entries.joinToString { "${it.value} ${it.key}" })
            }
        }
        tasks.named("check").configure { dependsOn(verify) }
    }
}

internal object Traceability {

    data class Entry(
        val id: String,
        val title: String,
        val status: String,
        val tests: List<String>,
        val note: String,
        val line: Int,
    )

    private val statuses = setOf("covered", "partial", "manual-evidence", "open", "superseded")

    /** Every id SPEC.md and ROADMAP.md define; the registry may add more, never fewer. */
    val requiredIds: Set<String> = buildSet {
        mapOf("G" to 6, "E" to 9, "S" to 6, "R" to 8, "I" to 5, "Y" to 5, "T" to 6).forEach { (area, count) ->
            (1..count).forEach { add("FR-$area$it") }
        }
        (1..14).forEach { add("NFR-$it") }
        (1..77).forEach { add("edge-$it") }
        (1..30).forEach { add("F%02d".format(it)) }
    }

    fun parse(lines: List<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        var fields = HashMap<String, String>()
        var tests = ArrayList<String>()
        var start = 0
        var inTests = false

        fun flush() {
            if (fields.isEmpty()) return
            entries += Entry(
                id = fields["id"].orEmpty(),
                title = fields["title"].orEmpty(),
                status = fields["status"].orEmpty(),
                tests = tests,
                note = fields["note"].orEmpty(),
                line = start,
            )
            fields = HashMap()
            tests = ArrayList()
        }

        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() || line.trimStart().startsWith("#") -> Unit
                line.startsWith("- id:") -> {
                    flush()
                    start = index + 1
                    inTests = false
                    fields["id"] = line.removePrefix("- id:").trim()
                }
                inTests && line.startsWith("    - ") -> tests += line.removePrefix("    - ").trim()
                line.startsWith("  ") && line.contains(":") -> {
                    val key = line.trim().substringBefore(":")
                    val value = line.trim().substringAfter(":").trim()
                    inTests = key == "tests"
                    if (key != "tests") fields[key] = value.removeSurrounding("\"")
                }
                else -> throw GradleException("docs/requirements.yaml:${index + 1}: cannot read line: $line")
            }
        }
        flush()
        return entries
    }

    fun problems(entries: List<Entry>, root: File): List<String> {
        val problems = ArrayList<String>()
        val fileCache = HashMap<String, String?>()
        entries.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "$it appears more than once" }
        (requiredIds - entries.map { it.id }.toSet()).sorted().forEach { problems += "$it is missing" }

        entries.forEach { entry ->
            val where = "${entry.id} (line ${entry.line})"
            if (entry.title.isBlank()) problems += "$where has no title"
            if (entry.status !in statuses) problems += "$where has unknown status '${entry.status}'"
            if (entry.status == "covered" && entry.tests.isEmpty()) problems += "$where is 'covered' but names no test"
            if (entry.status != "covered" && entry.note.isBlank()) {
                problems += "$where is '${entry.status}' and must say why in its note"
            }
            entry.tests.forEach { ref ->
                val path = ref.substringBefore(" :: ").trim()
                val needle = ref.substringAfter(" :: ", "").trim()
                val text = fileCache.getOrPut(path) { File(root, path).takeIf(File::isFile)?.readText() }
                when {
                    !ref.contains(" :: ") || needle.isEmpty() ->
                        problems += "$where: test reference must be '<file> :: <text>': $ref"
                    text == null -> problems += "$where: test file not found: $path"
                    !text.contains(needle) -> problems += "$where: '$needle' not found in $path"
                }
            }
        }
        return problems
    }
}
