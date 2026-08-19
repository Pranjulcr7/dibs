// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.InviteId
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

    /**
     * Establishes the group's metadata. The group *exists* implicitly with its
     * first op — metadata is decoration, so ops that arrive without it still
     * fold (SPEC §1: degrade, don't fail). A second CreateGroup quarantines;
     * concurrent creates on two devices resolve by replay order like any
     * duplicate.
     */
    @Serializable
    @SerialName("create_group")
    data class CreateGroup(
        val name: String,
        val emoji: String? = null,
        val colorArgb: Long? = null,
        val defaultCurrency: CurrencyCode,
    ) : OpPayload

    /** Partial metadata edit; null = unchanged, wrappers distinguish "cleared" (see [NoteEdit]). */
    @Serializable
    @SerialName("edit_group")
    data class EditGroup(
        val name: String? = null,
        val emoji: EmojiEdit? = null,
        val colorArgb: ColorEdit? = null,
        val defaultCurrency: CurrencyCode? = null,
    ) : OpPayload

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

    /** Undo for [DeactivateMember]; deactivation must be as reversible as it is safe. */
    @Serializable
    @SerialName("reactivate_member")
    data class ReactivateMember(val memberId: MemberId) : OpPayload

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

    /**
     * Opens the group to a joiner (SPEC §5.9). Defaults: single-use, 7-day
     * expiry — both explicit fields so validation reads only op contents.
     * Signed by the inviter's identity key, which must belong to a claimed
     * active member.
     */
    @Serializable
    @SerialName("create_invite")
    data class CreateInvite(
        val inviteId: InviteId,
        val inviterKey: IdentityKey,
        val maxUses: Int,
        val expiresAtMillis: Long,
        val signature: String,
    ) : OpPayload

    @Serializable
    @SerialName("revoke_invite")
    data class RevokeInvite(
        val inviteId: InviteId,
        val revokerKey: IdentityKey,
        val signature: String,
    ) : OpPayload

    /**
     * Binds an identity key to a member (SPEC §5.9): either claims an existing
     * unclaimed member ([displayName] null) or creates a new one. [inviteId] is
     * null only for the bootstrap claim — the group creator claiming themselves
     * while no claimed members exist yet.
     */
    @Serializable
    @SerialName("claim_member")
    data class ClaimMember(
        val memberId: MemberId,
        val displayName: String? = null,
        val inviteId: InviteId? = null,
        val claimantKey: IdentityKey,
        val signature: String,
    ) : OpPayload

    /** Lifts a pending joiner into full membership; only meaningful under approval mode. */
    @Serializable
    @SerialName("approve_member")
    data class ApproveMember(
        val memberId: MemberId,
        val approverKey: IdentityKey,
        val signature: String,
    ) : OpPayload

    /**
     * Removes a member outright — the invite-notification escape hatch. Valid
     * only for members with no financial history; anyone with history must be
     * deactivated instead (edge 13), which the fold enforces.
     */
    @Serializable
    @SerialName("remove_member")
    data class RemoveMember(
        val memberId: MemberId,
        val removerKey: IdentityKey,
        val signature: String,
    ) : OpPayload

    /** Per-group approval mode toggle (SPEC §5.9); LWW like other group settings. */
    @Serializable
    @SerialName("set_join_policy")
    data class SetJoinPolicy(
        val requiresApproval: Boolean,
        val signerKey: IdentityKey,
        val signature: String,
    ) : OpPayload

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
    /** Civil date of the expense (epoch day) — never an instant (D30). */
    val dateEpochDay: Long,
    val note: String? = null,
    /** Machine tag ("food", "travel", …); localized at display time. */
    val category: String? = null,
    /** App-private receipt image reference (M5); never a path outside app storage. */
    val receiptRef: String? = null,
    /**
     * The split mode used at entry ("equal", "exact", …) — an editor hint and
     * display detail only; [owedBy] is always the financial truth (D32).
     */
    val splitMode: String? = null,
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
    val category: CategoryEdit? = null,
)

/** Wrapper whose presence means "the category was changed", possibly cleared; see [NoteEdit]. */
@Serializable
data class CategoryEdit(val value: String?)

/** Wrapper whose presence means "the note was changed", possibly to null. */
@Serializable
data class NoteEdit(val value: String?)

/** Wrapper whose presence means "the emoji was changed", possibly cleared; see [NoteEdit]. */
@Serializable
data class EmojiEdit(val value: String?)

/** Wrapper whose presence means "the color was changed", possibly cleared; see [NoteEdit]. */
@Serializable
data class ColorEdit(val value: Long?)

/** An atomic replacement of an expense's money block; see [ExpenseEdit]. */
@Serializable
data class AmountsEdit(
    val currency: CurrencyCode,
    val total: Money,
    val paidBy: Map<MemberId, Money>,
    val owedBy: Map<MemberId, Money>,
    val splitMode: String? = null,
)
