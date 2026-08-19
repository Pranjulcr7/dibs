// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.balance.Balances
import app.dibs.core.domain.balance.Expense
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.balance.PairDebt
import app.dibs.core.domain.balance.PairwiseBalances
import app.dibs.core.domain.balance.SettlementPayment
import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settle.Settlement
import app.dibs.core.domain.settle.Transfer
import java.util.SortedMap
import java.util.TreeMap

/** One recorded name a member has had, kept so renames are auditable (case 19). */
data class NameChange(
    val name: String,
    val opId: OpId,
    val deviceId: DeviceId,
    val lamport: Long,
)

data class MemberState(
    val id: MemberId,
    val name: String,
    val active: Boolean,
    val nameHistory: List<NameChange>,
    /** Set when a device claimed this member (SPEC §5.9); null = local name record. */
    val identityKey: IdentityKey? = null,
    /** True while awaiting ApproveMember under approval mode. */
    val pending: Boolean = false,
    /** RemoveMember applied; hidden everywhere, unlike deactivation. */
    val removed: Boolean = false,
    /** Which invite the claim came through — feeds the "joined via your invite" notice. */
    val claimedViaInvite: InviteId? = null,
)

/** One entry in an expense's audit trail; every op that touched the expense appears (cases 20/21). */
data class ExpenseEvent(
    val kind: Kind,
    val opId: OpId,
    val deviceId: DeviceId,
    val lamport: Long,
    /** Display-only, like every wall clock — trash age, "edited yesterday". */
    val wallClockMillis: Long,
) {
    enum class Kind { CREATED, EDITED, DELETED, RESTORED }
}

data class ExpenseState(
    val id: ExpenseId,
    val fields: ExpenseFields,
    val deleted: Boolean,
    val history: List<ExpenseEvent>,
) {
    /** Devices that have touched this expense — the UI's "edited on another device" note (case 20). */
    val editingDevices: Set<DeviceId> get() = history.map { it.deviceId }.toSet()
}

data class SettlementState(
    val id: SettlementId,
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
    val currency: CurrencyCode,
    /** "payment" (null) or "writeoff" (edge 14). */
    val kind: String? = null,
)

/** Why an op could not be applied. Typed so the UI can say something specific, never "something went wrong". */
sealed interface QuarantineReason {
    data class UnknownMember(val memberId: MemberId) : QuarantineReason
    data class UnknownExpense(val expenseId: ExpenseId) : QuarantineReason
    data class UnknownSettlement(val settlementId: SettlementId) : QuarantineReason
    data object UnknownGroup : QuarantineReason
    data object InvalidAmounts : QuarantineReason
    data object DuplicateEntity : QuarantineReason

    // Identity and joining (SPEC §5.9, edge 74).
    data object InvalidSignature : QuarantineReason
    data class UnauthorizedActor(val key: IdentityKey) : QuarantineReason
    data class UnknownInvite(val inviteId: InviteId) : QuarantineReason
    data class InviteRevoked(val inviteId: InviteId) : QuarantineReason
    data class InviteExpired(val inviteId: InviteId) : QuarantineReason
    data class InviteExhausted(val inviteId: InviteId) : QuarantineReason
    data class AlreadyClaimed(val memberId: MemberId) : QuarantineReason
    data class KeyAlreadyBound(val key: IdentityKey) : QuarantineReason
    data class RemovalBlocked(val memberId: MemberId) : QuarantineReason
}

/** One invite's folded state (SPEC §5.9). [uses] counts applied claims, in replay order. */
data class InviteState(
    val id: InviteId,
    val inviterKey: IdentityKey,
    val inviterMemberId: MemberId?,
    val maxUses: Int,
    val expiresAtMillis: Long,
    val revoked: Boolean,
    val uses: Int,
)

/**
 * Group decoration set by [OpPayload.CreateGroup] (FR-G1). Nullable on
 * [GroupState] because a group exists from its first op; ops never wait for
 * metadata to fold.
 */
data class GroupMeta(
    val name: String,
    val emoji: String?,
    val colorArgb: Long?,
    val defaultCurrency: CurrencyCode,
    val adHoc: Boolean = false,
)

/**
 * An op that folded ops could not apply (unknown reference, invalid content).
 * Quarantined, never dropped and never fatal (case 25): the data may become
 * interpretable when more ops arrive, and a crash would take the whole group
 * down over one bad op.
 */
data class QuarantinedOp(
    val opId: OpId,
    val reason: QuarantineReason,
)

/**
 * The state of one group as folded from its op-log.
 *
 * Why sorted maps: this object is rendered, diffed, and canonically serialized;
 * iteration order must be a function of content, not of fold implementation
 * details (CLAUDE.md invariant 6).
 */
data class GroupState(
    val groupId: GroupId,
    val members: SortedMap<MemberId, MemberState>,
    val expenses: SortedMap<ExpenseId, ExpenseState>,
    val settlements: SortedMap<SettlementId, SettlementState>,
    val quarantined: List<QuarantinedOp>,
    val meta: GroupMeta? = null,
    val invites: SortedMap<InviteId, InviteState> = TreeMap(),
    /** Null until a SetJoinPolicy op ever ran — keeps pre-identity groups rendering unchanged. */
    val joinApprovalRequired: Boolean? = null,
    /** Null until a SetArchived op ever ran (FR-G4). */
    val archived: Boolean? = null,
) {

    /** Expenses visible in normal lists; tombstoned ones live in [trash]. */
    val activeExpenses: List<ExpenseState> get() = expenses.values.filterNot { it.deleted }

    /** Deleted-but-recoverable expenses (case 21); restoring is just another op. */
    val trash: List<ExpenseState> get() = expenses.values.filter { it.deleted }

    /**
     * Net balances derived from active expenses and settlements. Deleted and
     * quarantined data never counts — a debt from an expense you can't see
     * would be unverifiable, violating "never show a number the user can't
     * verify" (SPEC §1).
     */
    fun balances(): Map<CurrencyCode, List<MemberBalance>> = Balances.net(expenseRecords(), settlementRecords())

    /** Raw who-owes-whom, the default balances view (FR-S1). */
    fun pairwiseDebts(): Map<CurrencyCode, List<PairDebt>> =
        PairwiseBalances.compute(expenseRecords(), settlementRecords())

    /** Greedy minimal-ish transfers behind the opt-in simplify toggle (FR-S2). */
    fun suggestedTransfers(): Map<CurrencyCode, List<Transfer>> =
        balances().mapValues { (_, memberBalances) -> Settlement.simplify(memberBalances) }

    private fun expenseRecords() = activeExpenses.map {
        Expense(it.id, it.fields.currency, it.fields.paidBy, it.fields.owedBy)
    }

    private fun settlementRecords() = settlements.values.map {
        SettlementPayment(it.from, it.to, it.amount, it.currency)
    }

    /**
     * A canonical byte rendering of the complete state. Two devices that have
     * seen the same ops must produce identical bytes here — this is the
     * property the convergence simulation asserts, and later the basis for
     * cheap "are we in sync" comparisons.
     */
    // One conditional block per optional state section; splitting would hide the format.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun canonicalBytes(): ByteArray = buildString {
        append("state|").append(groupId.value).append('\n')
        // Emitted only when present: groups predating metadata ops (and the M2
        // golden vectors) must keep their exact canonical rendering.
        meta?.let { m ->
            append("g|").append(m.name.length).append(':').append(m.name)
                .append('|').append(m.emoji?.let { "${it.length}:$it" } ?: "null")
                .append('|').append(m.colorArgb?.toString() ?: "null")
                .append('|').append(m.defaultCurrency.value)
                .append('|').append(m.adHoc).append('\n')
        }
        joinApprovalRequired?.let { append("jp|").append(it).append('\n') }
        archived?.let { append("ar|").append(it).append('\n') }
        members.values.forEach { member ->
            append("m|").append(member.id.value).append('|').append(member.name.length).append(':')
                .append(member.name).append('|').append(member.active)
            member.nameHistory.forEach { change ->
                append("|h:").append(change.name.length).append(':').append(change.name)
                    .append(',').append(change.opId.value).append(',').append(change.deviceId.value)
                    .append(',').append(change.lamport)
            }
            // Identity segments render only on claimed/removed members, so every
            // pre-identity state keeps its exact historical bytes.
            member.identityKey?.let { append("|k:").append(it.hex) }
            member.claimedViaInvite?.let { append("|via:").append(it.value) }
            if (member.pending) append("|pending")
            if (member.removed) append("|removed")
            append('\n')
        }
        expenses.values.forEach { expense ->
            append("e|").append(expense.id.value).append('|').append(expense.deleted).append('|')
            appendFields(expense.fields)
            expense.history.forEach { event ->
                append("|h:").append(event.kind).append(',').append(event.opId.value)
                    .append(',').append(event.deviceId.value).append(',').append(event.lamport)
                    .append(',').append(event.wallClockMillis)
            }
            append('\n')
        }
        settlements.values.forEach { settlement ->
            append("s|").append(settlement.id.value).append('|').append(settlement.from.value)
                .append('|').append(settlement.to.value).append('|').append(settlement.amount.minorUnits)
                .append('|').append(settlement.currency.value)
                .append('|').append(settlement.kind ?: "null").append('\n')
        }
        invites.values.forEach { invite ->
            append("iv|").append(invite.id.value).append('|').append(invite.inviterKey.hex)
                .append('|').append(invite.inviterMemberId?.value ?: "null")
                .append('|').append(invite.maxUses).append('|').append(invite.expiresAtMillis)
                .append('|').append(invite.revoked).append('|').append(invite.uses).append('\n')
        }
        quarantined.forEach { entry ->
            append("q|").append(entry.opId.value).append('|').append(entry.reason).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendFields(fields: ExpenseFields) {
        append(fields.description.length).append(':').append(fields.description)
            .append('|').append(fields.currency.value)
            .append('|').append(fields.total.minorUnits)
            .append('|').append(fields.dateEpochDay)
            .append('|').append(fields.note?.let { "${it.length}:$it" } ?: "null")
            .append('|').append(fields.category ?: "null")
            .append('|').append(fields.receiptRef ?: "null")
            .append('|').append(fields.splitMode ?: "null")
        fields.paidBy.entries.sortedBy { it.key }.forEach { (member, amount) ->
            append("|p:").append(member.value).append(',').append(amount.minorUnits)
        }
        fields.owedBy.entries.sortedBy { it.key }.forEach { (member, amount) ->
            append("|o:").append(member.value).append(',').append(amount.minorUnits)
        }
    }

    companion object {
        fun empty(groupId: GroupId): GroupState = GroupState(groupId, TreeMap(), TreeMap(), TreeMap(), emptyList())
    }
}
