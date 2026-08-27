// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.sync

import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.SyncMetaStore
import app.dibs.core.domain.sync.VectorClock

/**
 * Orchestrates manual sync (FR-Y1): sealing the group's ops into a shareable
 * bundle, and applying a received bundle — with AUDIT T1's authorization gate
 * in the middle.
 *
 * **The T1 gate.** A bundle's signature proves *who* sealed it; whether that
 * key belongs in the group is decided against fold state — but against the
 * state *after* the bundle's own ops apply, computed in a dry-run fold that
 * touches nothing persistent. That order matters: a joiner's first bundle
 * carries their own `ClaimMember`, so pre-state membership would reject every
 * join. The dry-run keeps "never partially apply" (edge 27) intact — a
 * rejected sender leaves the ledger byte-identical. A group with *no* claimed
 * members yet has nobody to authenticate against, so its bundles apply
 * ungated — identity claims are opt-in (SPEC §5.9) and a pre-identity group
 * must keep syncing.
 *
 * Exports always carry the whole log: bundles are the snapshot handoff of
 * edge 31, and set-union ingestion makes redundancy free.
 */
class SyncService(
    private val ledger: Ledger,
    private val keys: GroupKeyStore,
    private val meta: SyncMetaStore,
    private val identity: IdentitySource,
    private val deviceId: DeviceId,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    sealed interface ImportOutcome {
        /**
         * [behind] is non-empty when the sender attested to ops this bundle did
         * not carry and we still lack (edge 75) — "some changes may be missing".
         */
        data class Imported(
            val groupId: GroupId,
            val opsAdded: Int,
            val skippedUnknownOps: Int,
            val behind: Map<DeviceId, Long>,
        ) : ImportOutcome

        /** We hold no key for this group — the join flow, not the import flow. */
        data class NoKeyForGroup(val groupId: GroupId) : ImportOutcome

        data object NotABundle : ImportOutcome

        data class NeedsNewerApp(val theirMajor: Int) : ImportOutcome

        /** Wrong key or any tampered byte — deliberately indistinguishable (edge 27). */
        data object RejectedTamperedOrWrongKey : ImportOutcome

        data object RejectedBadSignature : ImportOutcome

        /** Valid crypto, but the signer is not a claimed member of the group (AUDIT T1). */
        data class RejectedUnknownSender(val groupId: GroupId) : ImportOutcome
    }

    /** Seals every op of the group; creates the group key if none exists yet. */
    suspend fun exportBundle(groupId: GroupId): ByteArray {
        val ops = ledger.allOps(groupId)
        val bytes = SyncBundles.encode(
            groupId = groupId,
            ops = ops,
            groupKey = keys.getOrCreate(groupId),
            senderDevice = deviceId,
            sender = identity.keyPair(),
        )
        meta.recordShared(groupId, ops.size.toLong(), wallClock())
        return bytes
    }

    @Suppress("ReturnCount") // gates, in order; each exit is a distinct user-facing outcome
    suspend fun importBundle(bytes: ByteArray): ImportOutcome {
        val groupId = SyncBundles.peekGroupId(bytes) ?: return ImportOutcome.NotABundle
        val key = keys.keyFor(groupId) ?: return ImportOutcome.NoKeyForGroup(groupId)

        val decoded = when (val result = SyncBundles.decode(bytes, key)) {
            is SyncBundles.Result.Decoded -> result
            SyncBundles.Result.NotABundle -> return ImportOutcome.NotABundle
            is SyncBundles.Result.MajorVersionMismatch -> return ImportOutcome.NeedsNewerApp(result.theirs)
            SyncBundles.Result.WrongKeyOrTampered -> return ImportOutcome.RejectedTamperedOrWrongKey
            SyncBundles.Result.BadSignature -> return ImportOutcome.RejectedBadSignature
        }

        val existing = ledger.allOps(groupId)
        val dryRun = GroupLedger(groupId, deviceId)
        dryRun.receive(existing)
        dryRun.receive(decoded.ops)
        val claimedKeys = dryRun.state().members.values
            .filterNot { it.removed }
            .mapNotNull { it.identityKey }
            .toSet()
        if (claimedKeys.isNotEmpty() && decoded.senderKey !in claimedKeys) {
            return ImportOutcome.RejectedUnknownSender(groupId)
        }

        val known = existing.asSequence().map { it.id }.toSet()
        val fresh = decoded.ops.filterNot { it.id in known }
        ledger.ingest(groupId, fresh)
        meta.recordImported(groupId, wallClock())

        val localClock = VectorClock.of(ledger.allOps(groupId))
        return ImportOutcome.Imported(
            groupId = groupId,
            opsAdded = fresh.size,
            skippedUnknownOps = decoded.skippedUnknownOps,
            behind = localClock.behind(decoded.senderClock),
        )
    }
}
