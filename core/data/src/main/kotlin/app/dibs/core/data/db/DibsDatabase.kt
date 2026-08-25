// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.dibs.core.data.key.DatabaseKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        OpEntity::class,
        MemberEntity::class,
        ExpenseEntity::class,
        SettlementEntity::class,
        FoldMetaEntity::class,
        StateSnapshotEntity::class,
    ],
    version = DibsDatabase.VERSION,
    exportSchema = true,
)
abstract class DibsDatabase : RoomDatabase() {

    abstract fun opDao(): OpDao

    abstract fun stateDao(): StateDao

    companion object {
        const val VERSION = 2
        const val NAME = "dibs.db"

        /**
         * The production database: SQLCipher at rest with a Keystore-wrapped key
         * (NFR-11), a pre-migration backup taken *before* Room opens the file
         * (invariant 5), and every shipped migration registered.
         */
        @Suppress("SpreadOperator")
        fun buildEncrypted(context: Context, keyProvider: DatabaseKeyProvider): DibsDatabase {
            System.loadLibrary("sqlcipher")
            val dbFile = context.getDatabasePath(NAME)
            DatabaseBackupManager(
                databaseFile = dbFile,
                backupsDir = context.filesDir.resolve("db-backups"),
                versionFile = context.filesDir.resolve("$NAME.version"),
            ).backupBeforeOpenIfNeeded(VERSION)

            return Room.databaseBuilder(context, DibsDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(keyProvider.getOrCreatePassphrase()))
                // Spread is Room's API shape; the list is tiny and built once.
                .addMigrations(*Migrations.ALL.toTypedArray())
                .build()
        }
    }
}
