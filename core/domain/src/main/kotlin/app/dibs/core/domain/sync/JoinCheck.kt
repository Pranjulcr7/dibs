// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.MemberState
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId

/**
 * The joiner's pre-flight for an invite (SPEC §5.9): given the group data this
 * device currently holds, can a claim be written, and if not, what should the
 * person be told?
 *
 * Why this is separate from the fold: the fold decides, deterministically and
 * for every device, whether a `ClaimMember` op applies. But it can only decide
 * *after* the op is in the log, and a quarantined claim is permanent clutter in
 * a shared, append-only ledger. This check mirrors the fold's invite rules
 * locally so the join screen can explain the problem and write nothing. It is
 * deliberately never *more* permissive than the fold — and the caller still
 * confirms against the fold after appending, since another device's claim can
 * always win the race.
 */
object JoinCheck {

    sealed interface Result {

        /**
         * This device holds the group's key but none of its data yet: the
         * invite arrived as a bare link or QR. The inviter needs to share the
         * group's updates.
         */
        data object NeedsUpdates : Result

        /**
         * Data is here, but it does not contain this invite — it was sealed
         * before the invite was created. A fresher share fixes it.
         */
        data object InviteNotInData : Result

        data object InviteRevoked : Result

        data object InviteExpired : Result

        data object InviteUsedUp : Result

        /** Our identity is already bound to [memberId] here; joining again would only quarantine. */
        data class AlreadyMember(val memberId: MemberId, val pending: Boolean) : Result

        /**
         * A claim can be written. [claimable] are the name-only members the
         * joiner may turn out to be ("Bob" was added before Bob had the app);
         * choosing none creates a new member instead.
         */
        data class Ready(
            val groupName: String?,
            val inviterName: String?,
            val claimable: List<MemberState>,
            val approvalRequired: Boolean,
        ) : Result
    }

    /**
     * [nowMillis] stands in for the wall clock the claim op would carry — the
     * same value the fold will later compare against the invite's deadline, so
     * the two cannot disagree about expiry for a claim written now.
     */
    @Suppress("ReturnCount") // one guard per fold rule, in the fold's order
    fun evaluate(state: GroupState, inviteId: InviteId, myKey: IdentityKey, nowMillis: Long): Result {
        state.members.values.firstOrNull { it.identityKey == myKey && !it.removed }?.let {
            return Result.AlreadyMember(it.id, it.pending)
        }
        if (state.meta == null && state.members.isEmpty() && state.invites.isEmpty()) return Result.NeedsUpdates

        val invite = state.invites[inviteId] ?: return Result.InviteNotInData
        if (invite.revoked) return Result.InviteRevoked
        if (nowMillis > invite.expiresAtMillis) return Result.InviteExpired
        if (invite.uses >= invite.maxUses) return Result.InviteUsedUp

        return Result.Ready(
            groupName = state.meta?.name,
            inviterName = invite.inviterMemberId?.let { state.members[it]?.name },
            claimable = state.members.values.filter { it.identityKey == null && it.active && !it.removed },
            approvalRequired = state.joinApprovalRequired == true,
        )
    }
}
