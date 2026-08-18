// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard half of the migration harness (CLAUDE.md invariant 5), in place before
 * the first schema exists. It reads the exported Room schema JSONs and fails
 * the build whenever a schema version appears without a covering migration —
 * so forgetting a migration is a red test, not a runtime crash on someone's
 * phone. The executable half (walking real migrations with MigrationTestHelper)
 * lives in DibsDatabaseMigrationTest once a database class exists.
 */
class MigrationCoverageTest {

    private fun exportedSchemaVersions(): List<Int> {
        val schemasRoot = File("schemas")
        if (!schemasRoot.isDirectory) return emptyList()
        return schemasRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()
            .toList()
    }

    @Test
    fun `given exported schemas when versions are listed then they are contiguous starting at 1`() {
        val versions = exportedSchemaVersions()
        versions.forEachIndexed { index, version ->
            assertEquals(
                "Schema versions must be contiguous from 1; found $versions",
                index + 1,
                version,
            )
        }
    }

    @Test
    fun `given consecutive schema versions when checked then each pair has a registered migration`() {
        val versions = exportedSchemaVersions()
        val covered = Migrations.ALL.map { it.startVersion to it.endVersion }
        versions.zipWithNext().forEach { (from, to) ->
            assertTrue(
                "No migration registered for schema $from -> $to. " +
                    "Every schema change ships with a tested migration (CLAUDE.md invariant 5).",
                covered.contains(from to to),
            )
        }
    }

    @Test
    fun `given registered migrations when checked then none references a version without an exported schema`() {
        val versions = exportedSchemaVersions().toSet()
        Migrations.ALL.forEach { migration ->
            assertTrue(
                "Migration ${migration.startVersion}->${migration.endVersion} references versions " +
                    "with no exported schema; did the schema export get lost?",
                versions.contains(migration.startVersion) && versions.contains(migration.endVersion),
            )
        }
    }
}
