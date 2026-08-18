// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The financial facts the ledger records — small, mostly immutable, append-only.
 *
 * Why `@Serializable`: the data layer persists payloads as JSON. That stored
 * form is *not* what op ids are hashed over — ids come from the hand-rolled
 * canonical encoding in [OpFactory], so a serialization-library change can
 * never silently change every op's identity.
 */
@Serializable
sealed interface OpPayload {

    @Serializable
    @SerialName("add_member")
    data class AddMember(val memberId: MemberId, val name: String) : OpPayload

    @Serializable
    @SerialName("rename_member")
    data class RenameMember(val memberId: MemberId, val newName: String) : OpPayload

    /** Members with history are deactivated, never deleted (SPEC §6.2 case 13). */
    @Serializable
    @SerialName("deactivate_member")
    data class DeactivateMember(val memberId: MemberId) : OpPayload

    @Serializable
    @SerialName("add_expense")
    data class AddExpense(val expenseId: ExpenseId, val expense: ExpenseFields) : OpPayload

    /** Carries only the changed fields; unchanged fields are null (field-level LWW, case 20). */
    @Serializable
    @SerialName("edit_expense")
    data class EditExpense(val expenseId: ExpenseId, val edit: ExpenseEdit) : OpPayload

    /** A tombstone — nothing is ever physically removed, which is what makes trash trivial (case 21). */
    @Serializable
    @SerialName("delete_expense")
    data class DeleteExpense(val expenseId: ExpenseId) : OpPayload

    @Serializable
    @SerialName("restore_expense")
    data class RestoreExpense(val expenseId: ExpenseId) : OpPayload

    @Serializable
    @SerialName("record_settlement")
    data class RecordSettlement(
        val settlementId: SettlementId,
        val from: MemberId,
        val to: MemberId,
        val amount: Money,
        val currency: CurrencyCode,
    ) : OpPayload
}

/** The complete data of an expense as recorded in an [OpPayload.AddExpense]. */
@Serializable
data class ExpenseFields(
    val description: String,
    val currency: CurrencyCode,
    val total: Money,
    val paidBy: Map<MemberId, Money>,
    val owedBy: Map<MemberId, Money>,
    val dateEpochDay: Long,
    val note: String? = null,
)

/**
 * A partial edit: null means "unchanged". The money block — currency, total,
 * payers, split — changes as **one atomic field** ([amounts]) because its parts
 * are mutually constrained by the sum invariant; letting two devices win
 * different halves of it would manufacture an unbalanced expense
 * (DECISIONS.md D12). The note needs [NoteEdit] because "set the note to
 * nothing" must be distinguishable from "didn't touch the note".
 */
@Serializable
data class ExpenseEdit(
    val description: String? = null,
    val note: NoteEdit? = null,
    val dateEpochDay: Long? = null,
    val amounts: AmountsEdit? = null,
)

/** Wrapper whose presence means "the note was changed", possibly to null. */
@Serializable
data class NoteEdit(val value: String?)

/** An atomic replacement of an expense's money block; see [ExpenseEdit]. */
@Serializable
data class AmountsEdit(
    val currency: CurrencyCode,
    val total: Money,
    val paidBy: Map<MemberId, Money>,
    val owedBy: Map<MemberId, Money>,
)
