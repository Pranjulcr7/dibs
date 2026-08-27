// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.sync

import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.db.SyncMetaEntity
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.SyncMeta
import app.dibs.core.domain.sync.SyncMetaStore

/** [SyncMetaStore] over the `sync_meta` table. */
class DefaultSyncMetaStore(private val db: DibsDatabase) : SyncMetaStore {

    override suspend fun metaFor(groupId: GroupId): SyncMeta = db.syncMetaDao().metaFor(groupId.value)?.let {
        SyncMeta(it.sharedOpCount, it.lastSharedAtMillis, it.lastImportAtMillis)
    } ?: SyncMeta()

    override suspend fun recordShared(groupId: GroupId, opCount: Long, atMillis: Long) {
        val current = metaFor(groupId)
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                groupId = groupId.value,
                sharedOpCount = opCount,
                lastSharedAtMillis = atMillis,
                lastImportAtMillis = current.lastImportAtMillis,
            ),
        )
    }

    override suspend fun recordImported(groupId: GroupId, atMillis: Long) {
        val current = metaFor(groupId)
        db.syncMetaDao().upsert(
            SyncMetaEntity(
                groupId = groupId.value,
                sharedOpCount = current.sharedOpCount,
                lastSharedAtMillis = current.lastSharedAtMillis,
                lastImportAtMillis = atMillis,
            ),
        )
    }
}
