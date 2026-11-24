// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.model.GroupId

/**
 * The feature-facing face of manual sync (FR-Y1): seal a group into a
 * shareable bundle, apply a received one. Features depend on this seam only —
 * the implementation (crypto, T1 authorization, transports) lives in
 * `:core:sync` and is bound by the app, exactly like [SyncTransport] and
 * `ReceiptParser` (CLAUDE.md's swappable tiers).
 */
interface BundleSync {

    /** Every outcome is a distinct, user-explainable state — never a silent failure (FR-Y4). */
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
    suspend fun exportBundle(groupId: GroupId): ByteArray

    suspend fun importBundle(bytes: ByteArray): ImportOutcome
}
