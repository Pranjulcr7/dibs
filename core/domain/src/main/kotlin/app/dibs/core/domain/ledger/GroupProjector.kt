// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import java.util.SortedMap
import java.util.TreeMap

/**
 * The fold from ordered ops to [GroupState] (SPEC §5.5).
 *
 * Why a single-step pure `apply`: `project(ops) = ops.fold(empty, ::apply)`
 * means the data layer can cache a folded state and fold only a new suffix —
 * incremental recomputation falls out of the design instead of being a second
 * code path that can disagree with the first. Field-level last-writer-wins
 * falls out too: folding edits in the replay total order *is* LWW per field.
 *
 * Every decision in here — including which op gets quarantined when two ops
 * conflict — happens in replay order, so every device decides identically.
 */
@Suppress("TooManyFunctions") // one small private handler per payload type
object GroupProjector {

    /** Folds [orderedOps], which MUST be in [OpLog.TOTAL_ORDER]; callers get that from [OpLog.applicable]. */
    fun project(groupId: GroupId, orderedOps: List<Op>): GroupState =
        orderedOps.fold(GroupState.empty(groupId)) { state, op -> apply(state, op) }

    /** Applies one op. Never throws on op *content* — bad content quarantines (case 25). */
    @Suppress("CyclomaticComplexMethod") // exhaustive dispatch: one branch per payload kind
    fun apply(state: GroupState, op: Op): GroupState = when (val payload = op.payload) {
        is OpPayload.CreateGroup -> applyCreateGroup(state, op, payload)
        is OpPayload.EditGroup -> applyEditGroup(state, op, payload)
        is OpPayload.AddMember -> applyAddMember(state, op, payload)
        is OpPayload.RenameMember -> applyRenameMember(state, op, payload)
        is OpPayload.DeactivateMember -> applyMemberActivation(state, op, payload.memberId, active = false)
        is OpPayload.ReactivateMember -> applyMemberActivation(state, op, payload.memberId, active = true)
        is OpPayload.AddExpense -> applyAddExpense(state, op, payload)
        is OpPayload.EditExpense -> applyEditExpense(state, op, payload)
        is OpPayload.DeleteExpense -> applyExpenseMarker(state, op, payload.expenseId, deleted = true)
        is OpPayload.RestoreExpense -> applyExpenseMarker(state, op, payload.expenseId, deleted = false)
        is OpPayload.RecordSettlement -> applyRecordSettlement(state, op, payload)
        is OpPayload.CreateInvite -> applyCreateInvite(state, op, payload)
        is OpPayload.RevokeInvite -> applyRevokeInvite(state, op, payload)
        is OpPayload.ClaimMember -> applyClaimMember(state, op, payload)
        is OpPayload.ApproveMember -> applyApproveMember(state, op, payload)
        is OpPayload.RemoveMember -> applyRemoveMember(state, op, payload)
        is OpPayload.SetJoinPolicy -> applySetJoinPolicy(state, op, payload)
    }

    // -- identity and joining (SPEC §5.9) -----------------------------------
    // Every check reads only op contents and replay-order state, and signature
    // verification is a pure function — same op set, same verdicts, everywhere.

    private fun applyCreateInvite(state: GroupState, op: Op, payload: OpPayload.CreateInvite): GroupState {
        if (state.invites.containsKey(payload.inviteId)) return state.quarantine(op, QuarantineReason.DuplicateEntity)
        val message = SignedMessages.createInvite(
            op.groupId,
            payload.inviteId,
            payload.inviterKey,
            payload.maxUses,
            payload.expiresAtMillis,
        )
        if (!verified(payload.inviterKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        val inviter = claimedActiveMember(state, payload.inviterKey)
            ?: return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.inviterKey))
        if (payload.maxUses < 1) return state.quarantine(op, QuarantineReason.InviteExhausted(payload.inviteId))
        val invite = InviteState(
            id = payload.inviteId,
            inviterKey = payload.inviterKey,
            inviterMemberId = inviter.id,
            maxUses = payload.maxUses,
            expiresAtMillis = payload.expiresAtMillis,
            revoked = false,
            uses = 0,
        )
        return state.copy(invites = state.invites.plusSorted(payload.inviteId, invite))
    }

    private fun applyRevokeInvite(state: GroupState, op: Op, payload: OpPayload.RevokeInvite): GroupState {
        val invite = state.invites[payload.inviteId]
            ?: return state.quarantine(op, QuarantineReason.UnknownInvite(payload.inviteId))
        val message = SignedMessages.revokeInvite(op.groupId, payload.inviteId, payload.revokerKey)
        if (!verified(payload.revokerKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        claimedActiveMember(state, payload.revokerKey)
            ?: return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.revokerKey))
        return state.copy(invites = state.invites.plusSorted(payload.inviteId, invite.copy(revoked = true)))
    }

    // One guard per SPEC §5.9 validation rule, in fixed order.
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun applyClaimMember(state: GroupState, op: Op, payload: OpPayload.ClaimMember): GroupState {
        val message = SignedMessages.claimMember(
            op.groupId,
            payload.memberId,
            payload.displayName,
            payload.inviteId,
            payload.claimantKey,
        )
        if (!verified(payload.claimantKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        if (state.members.values.any { it.identityKey == payload.claimantKey && !it.removed }) {
            return state.quarantine(op, QuarantineReason.KeyAlreadyBound(payload.claimantKey))
        }

        val bootstrap = payload.inviteId == null
        var invites = state.invites
        if (bootstrap) {
            // Only the very first claim of a group may skip the invite (the creator).
            if (state.members.values.any { it.identityKey != null && !it.removed }) {
                return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.claimantKey))
            }
        } else {
            val inviteId = checkNotNull(payload.inviteId)
            val invite = state.invites[inviteId]
                ?: return state.quarantine(op, QuarantineReason.UnknownInvite(inviteId))
            if (invite.revoked) return state.quarantine(op, QuarantineReason.InviteRevoked(inviteId))
            // Deterministic soft expiry: the claim op's own (display-only) clock
            // against the invite's deadline — never a local clock (SPEC §5.9).
            if (op.wallClockMillis > invite.expiresAtMillis) {
                return state.quarantine(op, QuarantineReason.InviteExpired(inviteId))
            }
            if (invite.uses >= invite.maxUses) {
                return state.quarantine(op, QuarantineReason.InviteExhausted(inviteId))
            }
            invites = state.invites.plusSorted(inviteId, invite.copy(uses = invite.uses + 1))
        }

        val pending = !bootstrap && state.joinApprovalRequired == true
        val existing = state.members[payload.memberId]
        val member = when {
            existing != null -> {
                if (existing.identityKey != null || existing.removed) {
                    return state.quarantine(op, QuarantineReason.AlreadyClaimed(payload.memberId))
                }
                // Claiming an existing member keeps its name; displayName is
                // only for creating a new member.
                existing.copy(
                    identityKey = payload.claimantKey,
                    pending = pending,
                    claimedViaInvite = payload.inviteId,
                )
            }
            else -> {
                val name = payload.displayName
                    ?: return state.quarantine(op, QuarantineReason.UnknownMember(payload.memberId))
                MemberState(
                    id = payload.memberId,
                    name = name,
                    active = true,
                    nameHistory = listOf(NameChange(name, op.id, op.deviceId, op.lamport)),
                    identityKey = payload.claimantKey,
                    pending = pending,
                    claimedViaInvite = payload.inviteId,
                )
            }
        }
        return state.copy(
            members = state.members.plusSorted(payload.memberId, member),
            invites = invites,
        )
    }

    private fun applyApproveMember(state: GroupState, op: Op, payload: OpPayload.ApproveMember): GroupState {
        val member = state.members[payload.memberId]?.takeUnless { it.removed }
            ?: return state.quarantine(op, QuarantineReason.UnknownMember(payload.memberId))
        if (!member.pending) return state.quarantine(op, QuarantineReason.AlreadyClaimed(payload.memberId))
        val message = SignedMessages.approveMember(op.groupId, payload.memberId, payload.approverKey)
        if (!verified(payload.approverKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        val approver = claimedActiveMember(state, payload.approverKey)
        if (approver == null || approver.id == payload.memberId) {
            return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.approverKey))
        }
        return state.copy(members = state.members.plusSorted(payload.memberId, member.copy(pending = false)))
    }

    private fun applyRemoveMember(state: GroupState, op: Op, payload: OpPayload.RemoveMember): GroupState {
        val member = state.members[payload.memberId]?.takeUnless { it.removed }
            ?: return state.quarantine(op, QuarantineReason.UnknownMember(payload.memberId))
        val message = SignedMessages.removeMember(op.groupId, payload.memberId, payload.removerKey)
        if (!verified(payload.removerKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        claimedActiveMember(state, payload.removerKey)
            ?: return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.removerKey))
        val hasHistory = state.expenses.values.any { expense ->
            payload.memberId in expense.fields.paidBy || payload.memberId in expense.fields.owedBy
        } || state.settlements.values.any { it.from == payload.memberId || it.to == payload.memberId }
        if (hasHistory) return state.quarantine(op, QuarantineReason.RemovalBlocked(payload.memberId))
        return state.copy(
            members = state.members.plusSorted(payload.memberId, member.copy(removed = true, active = false)),
        )
    }

    private fun applySetJoinPolicy(state: GroupState, op: Op, payload: OpPayload.SetJoinPolicy): GroupState {
        val message = SignedMessages.setJoinPolicy(op.groupId, payload.requiresApproval, payload.signerKey)
        if (!verified(payload.signerKey, message, payload.signature)) {
            return state.quarantine(op, QuarantineReason.InvalidSignature)
        }
        claimedActiveMember(state, payload.signerKey)
            ?: return state.quarantine(op, QuarantineReason.UnauthorizedActor(payload.signerKey))
        return state.copy(joinApprovalRequired = payload.requiresApproval)
    }

    /** The member (if any) this key is bound to, counting only full members. */
    private fun claimedActiveMember(state: GroupState, key: IdentityKey): MemberState? =
        state.members.values.firstOrNull {
            it.identityKey == key && it.active && !it.pending && !it.removed
        }

    private fun verified(key: IdentityKey, message: ByteArray, signatureHex: String): Boolean = try {
        Identity.verify(key, message, HexBytes.fromHex(signatureHex))
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun applyCreateGroup(state: GroupState, op: Op, payload: OpPayload.CreateGroup): GroupState {
        if (state.meta != null) return state.quarantine(op, QuarantineReason.DuplicateEntity)
        return state.copy(
            meta = GroupMeta(payload.name, payload.emoji, payload.colorArgb, payload.defaultCurrency),
        )
    }

    private fun applyEditGroup(state: GroupState, op: Op, payload: OpPayload.EditGroup): GroupState {
        val meta = state.meta ?: return state.quarantine(op, QuarantineReason.UnknownGroup)
        return state.copy(
            meta = meta.copy(
                name = payload.name ?: meta.name,
                emoji = if (payload.emoji != null) payload.emoji.value else meta.emoji,
                colorArgb = if (payload.colorArgb != null) payload.colorArgb.value else meta.colorArgb,
                defaultCurrency = payload.defaultCurrency ?: meta.defaultCurrency,
            ),
        )
    }

    private fun applyMemberActivation(state: GroupState, op: Op, memberId: MemberId, active: Boolean): GroupState {
        val member = state.members[memberId]
            ?: return state.quarantine(op, QuarantineReason.UnknownMember(memberId))
        return state.copy(members = state.members.plusSorted(memberId, member.copy(active = active)))
    }

    private fun applyAddMember(state: GroupState, op: Op, payload: OpPayload.AddMember): GroupState {
        if (state.members.containsKey(payload.memberId)) {
            return state.quarantine(op, QuarantineReason.DuplicateEntity)
        }
        val member = MemberState(
            id = payload.memberId,
            name = payload.name,
            active = true,
            nameHistory = listOf(NameChange(payload.name, op.id, op.deviceId, op.lamport)),
        )
        return state.copy(members = state.members.plusSorted(payload.memberId, member))
    }

    private fun applyRenameMember(state: GroupState, op: Op, payload: OpPayload.RenameMember): GroupState {
        val member = state.members[payload.memberId]
            ?: return state.quarantine(op, QuarantineReason.UnknownMember(payload.memberId))
        val renamed = member.copy(
            name = payload.newName,
            nameHistory = member.nameHistory + NameChange(payload.newName, op.id, op.deviceId, op.lamport),
        )
        return state.copy(members = state.members.plusSorted(payload.memberId, renamed))
    }

    private fun applyAddExpense(state: GroupState, op: Op, payload: OpPayload.AddExpense): GroupState {
        if (state.expenses.containsKey(payload.expenseId)) {
            return state.quarantine(op, QuarantineReason.DuplicateEntity)
        }
        validateAmounts(state, payload.expense.paidBy, payload.expense.owedBy, payload.expense.total)
            ?.let { return state.quarantine(op, it) }
        val expense = ExpenseState(
            id = payload.expenseId,
            fields = payload.expense,
            deleted = false,
            history = listOf(ExpenseEvent(ExpenseEvent.Kind.CREATED, op.id, op.deviceId, op.lamport)),
        )
        return state.copy(expenses = state.expenses.plusSorted(payload.expenseId, expense))
    }

    private fun applyEditExpense(state: GroupState, op: Op, payload: OpPayload.EditExpense): GroupState {
        val expense = state.expenses[payload.expenseId]
            ?: return state.quarantine(op, QuarantineReason.UnknownExpense(payload.expenseId))
        val edit = payload.edit
        edit.amounts?.let { amounts ->
            validateAmounts(state, amounts.paidBy, amounts.owedBy, amounts.total)
                ?.let { return state.quarantine(op, it) }
        }
        // Folding in replay order makes each non-null field a last-writer-wins
        // register (case 20); untouched fields keep their current value.
        val fields = expense.fields.copy(
            description = edit.description ?: expense.fields.description,
            note = if (edit.note != null) edit.note.value else expense.fields.note,
            dateEpochDay = edit.dateEpochDay ?: expense.fields.dateEpochDay,
            currency = edit.amounts?.currency ?: expense.fields.currency,
            total = edit.amounts?.total ?: expense.fields.total,
            paidBy = edit.amounts?.paidBy ?: expense.fields.paidBy,
            owedBy = edit.amounts?.owedBy ?: expense.fields.owedBy,
        )
        val edited = expense.copy(
            fields = fields,
            history = expense.history + ExpenseEvent(ExpenseEvent.Kind.EDITED, op.id, op.deviceId, op.lamport),
        )
        return state.copy(expenses = state.expenses.plusSorted(payload.expenseId, edited))
    }

    /**
     * Tombstone or un-tombstone. A tombstone is only ever cleared by an explicit
     * restore, which is why a delete beats any concurrent edit (case 21): edits
     * change fields and history but nothing about them resurrects the expense.
     */
    private fun applyExpenseMarker(state: GroupState, op: Op, expenseId: ExpenseId, deleted: Boolean): GroupState {
        val expense = state.expenses[expenseId]
            ?: return state.quarantine(op, QuarantineReason.UnknownExpense(expenseId))
        val kind = if (deleted) ExpenseEvent.Kind.DELETED else ExpenseEvent.Kind.RESTORED
        val marked = expense.copy(
            deleted = deleted,
            history = expense.history + ExpenseEvent(kind, op.id, op.deviceId, op.lamport),
        )
        return state.copy(expenses = state.expenses.plusSorted(expenseId, marked))
    }

    private fun applyRecordSettlement(state: GroupState, op: Op, payload: OpPayload.RecordSettlement): GroupState {
        if (state.settlements.containsKey(payload.settlementId)) {
            return state.quarantine(op, QuarantineReason.DuplicateEntity)
        }
        firstUnknownMember(state, listOf(payload.from, payload.to))
            ?.let { return state.quarantine(op, QuarantineReason.UnknownMember(it)) }
        if (!payload.amount.isPositive || payload.from == payload.to) {
            return state.quarantine(op, QuarantineReason.InvalidAmounts)
        }
        val settlement = SettlementState(
            payload.settlementId,
            payload.from,
            payload.to,
            payload.amount,
            payload.currency,
        )
        return state.copy(settlements = state.settlements.plusSorted(payload.settlementId, settlement))
    }

    /** Null when valid; otherwise the deterministic reason (lowest unknown member id first). */
    private fun validateAmounts(
        state: GroupState,
        paidBy: Map<MemberId, Money>,
        owedBy: Map<MemberId, Money>,
        total: Money,
    ): QuarantineReason? {
        firstUnknownMember(state, paidBy.keys + owedBy.keys)
            ?.let { return QuarantineReason.UnknownMember(it) }
        if (paidBy.isEmpty()) return QuarantineReason.InvalidAmounts
        if (paidBy.values.any { it.isNegative } || owedBy.values.any { it.isNegative } || total.isNegative) {
            return QuarantineReason.InvalidAmounts
        }
        return try {
            val paid = paidBy.values.fold(Money.ZERO, Money::plus)
            val owed = owedBy.values.fold(Money.ZERO, Money::plus)
            if (paid != owed || paid != total) QuarantineReason.InvalidAmounts else null
        } catch (_: ArithmeticException) {
            QuarantineReason.InvalidAmounts
        }
    }

    private fun firstUnknownMember(state: GroupState, members: Collection<MemberId>): MemberId? =
        members.filterNot { state.members.containsKey(it) }.minOrNull()

    private fun GroupState.quarantine(op: Op, reason: QuarantineReason): GroupState =
        copy(quarantined = quarantined + QuarantinedOp(op.id, reason))

    private fun <K : Comparable<K>, V> SortedMap<K, V>.plusSorted(key: K, value: V): SortedMap<K, V> =
        TreeMap(this).also { it[key] = value }
}
