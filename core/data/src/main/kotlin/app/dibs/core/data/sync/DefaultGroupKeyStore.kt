// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.sync

import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.db.GroupKeyEntity
import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.GroupKeyStore
import java.security.SecureRandom

/**
 * [GroupKeyStore] over the `group_keys` table. Uniqueness is enforced by the
 * database, not by races: inserts are IGNORE-on-conflict, so two concurrent
 * [getOrCreate] calls both end up returning whichever key landed first.
 */
class DefaultGroupKeyStore(
    private val db: DibsDatabase,
    private val random: SecureRandom = SecureRandom(),
) : GroupKeyStore {

    override suspend fun keyFor(groupId: GroupId): ByteArray? =
        db.groupKeyDao().keyFor(groupId.value)?.let { HexBytes.fromHex(it.keyHex) }

    override suspend fun getOrCreate(groupId: GroupId): ByteArray {
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        db.groupKeyDao().insertIfAbsent(GroupKeyEntity(groupId.value, HexBytes.toHex(fresh)))
        // Read back rather than trusting `fresh`: a concurrent creator may have won.
        return checkNotNull(keyFor(groupId)) { "group key vanished after insert" }
    }

    override suspend fun adopt(groupId: GroupId, key: ByteArray) {
        require(key.size == KEY_BYTES) { "group key must be $KEY_BYTES bytes, got ${key.size}" }
        db.groupKeyDao().insertIfAbsent(GroupKeyEntity(groupId.value, HexBytes.toHex(key)))
        val held = checkNotNull(keyFor(groupId))
        check(held.contentEquals(key)) {
            // Two different keys for one group means someone is being fed a
            // different group — this must surface, never resolve silently.
            "group ${groupId.value} already holds a different key"
        }
    }

    override suspend fun all(): Map<GroupId, ByteArray> =
        db.groupKeyDao().all().associate { GroupId(it.groupId) to HexBytes.fromHex(it.keyHex) }

    private companion object {
        const val KEY_BYTES = 32
    }
}
