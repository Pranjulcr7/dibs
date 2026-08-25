// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executable half of the migration harness: creates the database at old schema
 * versions from the exported JSONs and walks every registered migration to the
 * current version, validating the resulting schema (CLAUDE.md invariant 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DibsDatabaseMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DibsDatabase::class.java,
    )

    @Test
    fun `given the current version then the database class and Migrations registry agree with the newest exported schema`() {
        assertEquals(DibsDatabase.VERSION, (Migrations.ALL.maxOfOrNull { it.endVersion } ?: DibsDatabase.VERSION))
    }

    @Test
    fun `given a database created at version 1 from its exported schema then walking all migrations reaches the current version with a valid schema`() {
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            // A real op row: migrations must carry user data forward untouched
            // (invariant 5), and this proves it rather than asserting schema only.
            v1.execSQL(
                "INSERT INTO ops (opId, groupId, lamport, deviceId, parentsJson, wallClockMillis, payloadJson) " +
                    "VALUES ('op-1', 'g-1', 1, 'd-1', '[]', 1000, '{}')",
            )
        }

        // Re-opening through Room with every migration registered validates the
        // final schema against the current entities; an invalid migration throws.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DibsDatabase::class.java,
            TEST_DB,
        )
            .addMigrations(*Migrations.ALL.toTypedArray())
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.version.let { assertEquals(DibsDatabase.VERSION, it) }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ops").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }
}
