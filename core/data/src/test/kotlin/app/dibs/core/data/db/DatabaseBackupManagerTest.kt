// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseBackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dbFile: java.io.File
    private lateinit var backupsDir: java.io.File
    private lateinit var versionFile: java.io.File
    private lateinit var manager: DatabaseBackupManager

    @Before
    fun setUp() {
        dbFile = tmp.newFile("dibs.db")
        backupsDir = tmp.newFolder("backups")
        versionFile = java.io.File(tmp.root, "dibs.db.version")
        manager = DatabaseBackupManager(dbFile, backupsDir, versionFile)
    }

    @Test
    fun `given a fresh install when opening at version 1 then no backup is taken and the version is recorded`() {
        val result = manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        assertEquals(DatabaseBackupManager.Result.FirstRun, result)
        assertEquals("1", versionFile.readText())
        assertEquals(0, backupsDir.listFiles()!!.size)
    }

    @Test
    fun `given an unchanged version when opening again then no backup is taken`() {
        manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        val result = manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        assertEquals(DatabaseBackupManager.Result.NoChange, result)
        assertEquals(0, backupsDir.listFiles()!!.size)
    }

    @Test
    fun `given a version bump when opening then the database file is copied before migration and the version updated`() {
        dbFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        val result = manager.backupBeforeOpenIfNeeded(currentVersion = 2)

        val backedUp = result as DatabaseBackupManager.Result.BackedUp
        assertTrue(backedUp.file.exists())
        assertEquals("dibs-pre-migration-v1-to-v2.db", backedUp.file.name)
        assertTrue(backedUp.file.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals("2", versionFile.readText())
    }

    @Test
    fun `given successive version bumps when opening then each bump keeps its own backup`() {
        dbFile.writeBytes(byteArrayOf(1))
        manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        manager.backupBeforeOpenIfNeeded(currentVersion = 2)
        dbFile.writeBytes(byteArrayOf(2))
        manager.backupBeforeOpenIfNeeded(currentVersion = 3)

        val names = backupsDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("dibs-pre-migration-v1-to-v2.db", "dibs-pre-migration-v2-to-v3.db"), names)
    }

    @Test
    fun `given a recorded version but no database file when opening then only the version is updated`() {
        manager.backupBeforeOpenIfNeeded(currentVersion = 1)
        dbFile.delete()
        val result = manager.backupBeforeOpenIfNeeded(currentVersion = 2)
        assertEquals(DatabaseBackupManager.Result.DatabaseMissing, result)
        assertEquals("2", versionFile.readText())
        assertEquals(0, backupsDir.listFiles()!!.size)
    }

    @Test
    fun `given a downgrade when opening then a backup is still taken because any version change is risky`() {
        dbFile.writeBytes(byteArrayOf(9))
        manager.backupBeforeOpenIfNeeded(currentVersion = 5)
        val result = manager.backupBeforeOpenIfNeeded(currentVersion = 4)
        val backedUp = result as DatabaseBackupManager.Result.BackedUp
        assertEquals("dibs-pre-migration-v5-to-v4.db", backedUp.file.name)
    }
}
