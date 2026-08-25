// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import androidx.room.migration.Migration

/**
 * Every Room migration this app has ever shipped, in order.
 *
 * Why a single registry: the migration test harness ([MigrationCoverageTest]
 * and the MigrationTestHelper walk) validates this list against the exported
 * schema JSONs, so a schema bump without a migration here fails the build
 * (CLAUDE.md invariant 5). Add to this list; never remove from it — users
 * upgrade from arbitrarily old versions.
 */
object Migrations {

    /**
     * v2: the cold-open state snapshot (ISSUES #19). Purely additive — a cache
     * table refilled from the op log on the next open — so existing data is
     * untouched (invariant 5).
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `state_snapshots` (" +
                    "`groupId` TEXT NOT NULL, " +
                    "`codecVersion` INTEGER NOT NULL, " +
                    "`opRowCount` INTEGER NOT NULL, " +
                    "`appliedOpCount` INTEGER NOT NULL, " +
                    "`lastAppliedOpId` TEXT, " +
                    "`maxLamport` INTEGER NOT NULL, " +
                    "`headsJson` TEXT NOT NULL, " +
                    "`stateJson` TEXT NOT NULL, " +
                    "PRIMARY KEY(`groupId`))",
            )
        }
    }

    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
