// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.SyncMeta
import app.dibs.core.domain.sync.SyncMetaStore

/** A fixed identity: the same master bytes always derive the same keypair (SPEC §5.9). */
class FakeIdentitySource(masterByte: Int = 42) : IdentitySource {
    private val keyPair = Identity.keyPairFromMaster(ByteArray(MASTER_BYTES) { masterByte.toByte() })
    override suspend fun keyPair(): IdentityKeyPair = keyPair

    private companion object {
        const val MASTER_BYTES = 32
    }
}

/** In-memory [GroupKeyStore] with the production contract: one key per group, conflicts are loud. */
class FakeGroupKeyStore : GroupKeyStore {
    private val keys = LinkedHashMap<GroupId, ByteArray>()
    private var next = 1

    override suspend fun keyFor(groupId: GroupId): ByteArray? = keys[groupId]

    override suspend fun getOrCreate(groupId: GroupId): ByteArray =
        keys.getOrPut(groupId) { ByteArray(KEY_BYTES) { (next++).toByte() } }

    override suspend fun adopt(groupId: GroupId, key: ByteArray) {
        val held = keys.getOrPut(groupId) { key }
        check(held.contentEquals(key)) { "group ${groupId.value} already holds a different key" }
    }

    override suspend fun all(): Map<GroupId, ByteArray> = keys.toMap()

    private companion object {
        const val KEY_BYTES = 32
    }
}

class FakeSyncMetaStore : SyncMetaStore {
    private val metas = LinkedHashMap<GroupId, SyncMeta>()

    override suspend fun metaFor(groupId: GroupId): SyncMeta = metas[groupId] ?: SyncMeta()

    override suspend fun recordShared(groupId: GroupId, opCount: Long, atMillis: Long) {
        metas[groupId] = metaFor(groupId).copy(sharedOpCount = opCount, lastSharedAtMillis = atMillis)
    }

    override suspend fun recordImported(groupId: GroupId, atMillis: Long) {
        metas[groupId] = metaFor(groupId).copy(lastImportAtMillis = atMillis)
    }
}

/**
 * Scripted [BundleSync]: the real sealing and gating live in `:core:sync` and
 * are tested there; ViewModel tests only need to see that calls arrive and
 * outcomes surface. Exporting records a share the way the real service does,
 * so the sync chip can be observed reacting to it.
 */
class FakeBundleSync(
    private val ledger: FakeLedger,
    private val meta: SyncMetaStore,
    var nextImportOutcome: BundleSync.ImportOutcome = BundleSync.ImportOutcome.NotABundle,
) : BundleSync {
    val imported = ArrayList<ByteArray>()

    override suspend fun exportBundle(groupId: GroupId): ByteArray {
        meta.recordShared(groupId, ledger.opCount(groupId), atMillis = SHARED_AT)
        return BUNDLE_BYTES
    }

    override suspend fun importBundle(bytes: ByteArray): BundleSync.ImportOutcome {
        imported += bytes
        return nextImportOutcome
    }

    companion object {
        const val SHARED_AT = 7_000L
        val BUNDLE_BYTES = byteArrayOf(1, 2, 3)
    }
}
