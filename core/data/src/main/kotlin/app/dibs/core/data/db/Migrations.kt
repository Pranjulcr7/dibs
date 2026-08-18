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
    val ALL: List<Migration> = emptyList()
}
