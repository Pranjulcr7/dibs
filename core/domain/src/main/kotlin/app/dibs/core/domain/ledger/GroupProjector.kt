// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

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
    fun apply(state: GroupState, op: Op): GroupState = when (val payload = op.payload) {
        is OpPayload.AddMember -> applyAddMember(state, op, payload)
        is OpPayload.RenameMember -> applyRenameMember(state, op, payload)
        is OpPayload.DeactivateMember -> applyDeactivateMember(state, op, payload)
        is OpPayload.AddExpense -> applyAddExpense(state, op, payload)
        is OpPayload.EditExpense -> applyEditExpense(state, op, payload)
        is OpPayload.DeleteExpense -> applyExpenseMarker(state, op, payload.expenseId, deleted = true)
        is OpPayload.RestoreExpense -> applyExpenseMarker(state, op, payload.expenseId, deleted = false)
        is OpPayload.RecordSettlement -> applyRecordSettlement(state, op, payload)
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

    private fun applyDeactivateMember(state: GroupState, op: Op, payload: OpPayload.DeactivateMember): GroupState {
        val member = state.members[payload.memberId]
            ?: return state.quarantine(op, QuarantineReason.UnknownMember(payload.memberId))
        return state.copy(members = state.members.plusSorted(payload.memberId, member.copy(active = false)))
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
