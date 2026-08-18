// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.balance.Balances
import app.dibs.core.domain.balance.Expense
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.balance.SettlementPayment
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
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
)

/** One entry in an expense's audit trail; every op that touched the expense appears (cases 20/21). */
data class ExpenseEvent(
    val kind: Kind,
    val opId: OpId,
    val deviceId: DeviceId,
    val lamport: Long,
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
)

/** Why an op could not be applied. Typed so the UI can say something specific, never "something went wrong". */
sealed interface QuarantineReason {
    data class UnknownMember(val memberId: MemberId) : QuarantineReason
    data class UnknownExpense(val expenseId: ExpenseId) : QuarantineReason
    data class UnknownSettlement(val settlementId: SettlementId) : QuarantineReason
    data object UnknownGroup : QuarantineReason
    data object InvalidAmounts : QuarantineReason
    data object DuplicateEntity : QuarantineReason
}

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
    fun balances(): Map<CurrencyCode, List<MemberBalance>> {
        val expenseRecords = activeExpenses.map {
            Expense(it.id, it.fields.currency, it.fields.paidBy, it.fields.owedBy)
        }
        val settlementRecords = settlements.values.map {
            SettlementPayment(it.from, it.to, it.amount, it.currency)
        }
        return Balances.net(expenseRecords, settlementRecords)
    }

    /**
     * A canonical byte rendering of the complete state. Two devices that have
     * seen the same ops must produce identical bytes here — this is the
     * property the convergence simulation asserts, and later the basis for
     * cheap "are we in sync" comparisons.
     */
    fun canonicalBytes(): ByteArray = buildString {
        append("state|").append(groupId.value).append('\n')
        // Emitted only when present: groups predating metadata ops (and the M2
        // golden vectors) must keep their exact canonical rendering.
        meta?.let { m ->
            append("g|").append(m.name.length).append(':').append(m.name)
                .append('|').append(m.emoji?.let { "${it.length}:$it" } ?: "null")
                .append('|').append(m.colorArgb?.toString() ?: "null")
                .append('|').append(m.defaultCurrency.value).append('\n')
        }
        members.values.forEach { member ->
            append("m|").append(member.id.value).append('|').append(member.name.length).append(':')
                .append(member.name).append('|').append(member.active)
            member.nameHistory.forEach { change ->
                append("|h:").append(change.name.length).append(':').append(change.name)
                    .append(',').append(change.opId.value).append(',').append(change.deviceId.value)
                    .append(',').append(change.lamport)
            }
            append('\n')
        }
        expenses.values.forEach { expense ->
            append("e|").append(expense.id.value).append('|').append(expense.deleted).append('|')
            appendFields(expense.fields)
            expense.history.forEach { event ->
                append("|h:").append(event.kind).append(',').append(event.opId.value)
                    .append(',').append(event.deviceId.value).append(',').append(event.lamport)
            }
            append('\n')
        }
        settlements.values.forEach { settlement ->
            append("s|").append(settlement.id.value).append('|').append(settlement.from.value)
                .append('|').append(settlement.to.value).append('|').append(settlement.amount.minorUnits)
                .append('|').append(settlement.currency.value).append('\n')
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
