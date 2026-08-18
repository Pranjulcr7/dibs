// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import java.io.File

/**
 * Copies the database file aside before any schema-version change touches it.
 *
 * Why this exists: no migration may destroy user data (CLAUDE.md invariant 5),
 * and the only backup that can save a botched migration is one taken *before*
 * the migration runs. Why a sidecar version file instead of reading
 * `user_version` from the SQLite header: SQLCipher encrypts the header, so the
 * file itself is opaque without the key.
 *
 * Call [backupBeforeOpenIfNeeded] before the database is opened. A backup is
 * taken on *any* version change, downgrades included — every version change is
 * a moment the file can be corrupted.
 */
class DatabaseBackupManager(
    private val databaseFile: File,
    private val backupsDir: File,
    private val versionFile: File,
) {

    sealed interface Result {
        /** No recorded version yet — nothing to protect. */
        data object FirstRun : Result

        /** Version unchanged — no risk, no copy. */
        data object NoChange : Result

        /** Version changed but there is no database file to copy (e.g. cleared data). */
        data object DatabaseMissing : Result

        /** The pre-migration copy that was written. */
        data class BackedUp(val file: File) : Result
    }

    fun backupBeforeOpenIfNeeded(currentVersion: Int): Result {
        val recorded = versionFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        val result = when {
            recorded == null -> Result.FirstRun
            recorded == currentVersion -> Result.NoChange
            !databaseFile.isFile -> Result.DatabaseMissing
            else -> {
                backupsDir.mkdirs()
                val target = File(backupsDir, "dibs-pre-migration-v$recorded-to-v$currentVersion.db")
                databaseFile.copyTo(target, overwrite = true)
                Result.BackedUp(target)
            }
        }
        if (result != Result.NoChange) {
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(currentVersion.toString())
        }
        return result
    }
}
