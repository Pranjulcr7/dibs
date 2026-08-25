// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseEvent
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.ExpenseState
import app.dibs.core.domain.ledger.GroupMeta
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.InviteState
import app.dibs.core.domain.ledger.MemberState
import app.dibs.core.domain.ledger.NameChange
import app.dibs.core.domain.ledger.OpId
import app.dibs.core.domain.ledger.QuarantineReason
import app.dibs.core.domain.ledger.QuarantinedOp
import app.dibs.core.domain.ledger.SettlementState
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TreeMap

/**
 * Serializes a complete folded [GroupState] for the cold-open snapshot
 * (ISSUES #19): one blob instead of refolding thousands of ops.
 *
 * This is a **cache format, not a wire or backup format** — nothing but this
 * process ever reads it, and [VERSION] gates it: any shape change bumps the
 * version and stale snapshots are simply refolded from the op log, which
 * remains the only source of truth. The codec must be *lossless* over the
 * whole state tree; the roundtrip test asserts full structural equality, not
 * just the canonical rendering.
 */
internal object GroupStateCodec {

    /** Bump on any DTO shape change; old snapshots then refold instead of misparse. */
    const val VERSION = 1

    private val json = Json

    fun encode(state: GroupState): String = json.encodeToString(state.toDto())

    fun decode(groupId: GroupId, text: String): GroupState = json.decodeFromString<StateDto>(text).toState(groupId)

    // -- DTOs -----------------------------------------------------------------

    @Serializable
    private data class StateDto(
        val members: List<MemberDto>,
        val expenses: List<ExpenseDto>,
        val settlements: List<SettlementDto>,
        val quarantined: List<QuarantinedDto>,
        val meta: MetaDto? = null,
        val invites: List<InviteDto> = emptyList(),
        val joinApprovalRequired: Boolean? = null,
        val archived: Boolean? = null,
    )

    @Serializable
    private data class MemberDto(
        val id: String,
        val name: String,
        val active: Boolean,
        val nameHistory: List<NameChangeDto>,
        val identityKeyHex: String? = null,
        val pending: Boolean = false,
        val removed: Boolean = false,
        val claimedViaInvite: String? = null,
    )

    @Serializable
    private data class NameChangeDto(val name: String, val opId: String, val deviceId: String, val lamport: Long)

    @Serializable
    private data class ExpenseDto(
        val id: String,
        val fields: ExpenseFields,
        val deleted: Boolean,
        val history: List<EventDto>,
    )

    @Serializable
    private data class EventDto(
        val kind: String,
        val opId: String,
        val deviceId: String,
        val lamport: Long,
        val wallClockMillis: Long,
    )

    @Serializable
    private data class SettlementDto(
        val id: String,
        val from: String,
        val to: String,
        val amountMinor: Long,
        val currency: String,
        val kind: String? = null,
    )

    @Serializable
    private data class InviteDto(
        val id: String,
        val inviterKeyHex: String,
        val inviterMemberId: String? = null,
        val maxUses: Int,
        val expiresAtMillis: Long,
        val revoked: Boolean,
        val uses: Int,
    )

    @Serializable
    private data class MetaDto(
        val name: String,
        val emoji: String? = null,
        val colorArgb: Long? = null,
        val defaultCurrency: String,
        val adHoc: Boolean = false,
    )

    /** `arg` is the single id/key each parameterized reason carries; null for the object reasons. */
    @Serializable
    private data class QuarantinedDto(val opId: String, val reason: String, val arg: String? = null)

    // -- mapping --------------------------------------------------------------

    private fun GroupState.toDto() = StateDto(
        members = members.values.map { member ->
            MemberDto(
                id = member.id.value,
                name = member.name,
                active = member.active,
                nameHistory = member.nameHistory.map {
                    NameChangeDto(it.name, it.opId.value, it.deviceId.value, it.lamport)
                },
                identityKeyHex = member.identityKey?.hex,
                pending = member.pending,
                removed = member.removed,
                claimedViaInvite = member.claimedViaInvite?.value,
            )
        },
        expenses = expenses.values.map { expense ->
            ExpenseDto(
                id = expense.id.value,
                fields = expense.fields,
                deleted = expense.deleted,
                history = expense.history.map {
                    EventDto(it.kind.name, it.opId.value, it.deviceId.value, it.lamport, it.wallClockMillis)
                },
            )
        },
        settlements = settlements.values.map {
            SettlementDto(it.id.value, it.from.value, it.to.value, it.amount.minorUnits, it.currency.value, it.kind)
        },
        quarantined = quarantined.map { QuarantinedDto(it.opId.value, it.reason.tag(), it.reason.arg()) },
        meta = meta?.let { MetaDto(it.name, it.emoji, it.colorArgb, it.defaultCurrency.value, it.adHoc) },
        invites = invites.values.map {
            InviteDto(
                id = it.id.value,
                inviterKeyHex = it.inviterKey.hex,
                inviterMemberId = it.inviterMemberId?.value,
                maxUses = it.maxUses,
                expiresAtMillis = it.expiresAtMillis,
                revoked = it.revoked,
                uses = it.uses,
            )
        },
        joinApprovalRequired = joinApprovalRequired,
        archived = archived,
    )

    private fun StateDto.toState(groupId: GroupId) = GroupState(
        groupId = groupId,
        members = members.associateTo(TreeMap()) { dto ->
            MemberId(dto.id) to MemberState(
                id = MemberId(dto.id),
                name = dto.name,
                active = dto.active,
                nameHistory = dto.nameHistory.map {
                    NameChange(it.name, OpId(it.opId), DeviceId(it.deviceId), it.lamport)
                },
                identityKey = dto.identityKeyHex?.let(::IdentityKey),
                pending = dto.pending,
                removed = dto.removed,
                claimedViaInvite = dto.claimedViaInvite?.let(::InviteId),
            )
        },
        expenses = expenses.associateTo(TreeMap()) { dto ->
            ExpenseId(dto.id) to ExpenseState(
                id = ExpenseId(dto.id),
                fields = dto.fields,
                deleted = dto.deleted,
                history = dto.history.map {
                    ExpenseEvent(
                        ExpenseEvent.Kind.valueOf(it.kind),
                        OpId(it.opId),
                        DeviceId(it.deviceId),
                        it.lamport,
                        it.wallClockMillis,
                    )
                },
            )
        },
        settlements = settlements.associateTo(TreeMap()) { dto ->
            SettlementId(dto.id) to SettlementState(
                id = SettlementId(dto.id),
                from = MemberId(dto.from),
                to = MemberId(dto.to),
                amount = Money(dto.amountMinor),
                currency = CurrencyCode(dto.currency),
                kind = dto.kind,
            )
        },
        quarantined = quarantined.map { QuarantinedOp(OpId(it.opId), reasonOf(it.reason, it.arg)) },
        meta = meta?.let { GroupMeta(it.name, it.emoji, it.colorArgb, CurrencyCode(it.defaultCurrency), it.adHoc) },
        invites = invites.associateTo(TreeMap()) { dto ->
            InviteId(dto.id) to InviteState(
                id = InviteId(dto.id),
                inviterKey = IdentityKey(dto.inviterKeyHex),
                inviterMemberId = dto.inviterMemberId?.let(::MemberId),
                maxUses = dto.maxUses,
                expiresAtMillis = dto.expiresAtMillis,
                revoked = dto.revoked,
                uses = dto.uses,
            )
        },
        joinApprovalRequired = joinApprovalRequired,
        archived = archived,
    )

    // Exhaustive both ways: adding a QuarantineReason without updating these is a
    // compile error on `tag`, and an IllegalStateException in tests on `reasonOf`.
    @Suppress("CyclomaticComplexMethod") // one branch per sealed variant; a table would hide exhaustiveness
    private fun QuarantineReason.tag(): String = when (this) {
        is QuarantineReason.UnknownMember -> "unknown_member"
        is QuarantineReason.UnknownExpense -> "unknown_expense"
        is QuarantineReason.UnknownSettlement -> "unknown_settlement"
        QuarantineReason.UnknownGroup -> "unknown_group"
        QuarantineReason.InvalidAmounts -> "invalid_amounts"
        QuarantineReason.DuplicateEntity -> "duplicate_entity"
        QuarantineReason.InvalidSignature -> "invalid_signature"
        is QuarantineReason.UnauthorizedActor -> "unauthorized_actor"
        is QuarantineReason.UnknownInvite -> "unknown_invite"
        is QuarantineReason.InviteRevoked -> "invite_revoked"
        is QuarantineReason.InviteExpired -> "invite_expired"
        is QuarantineReason.InviteExhausted -> "invite_exhausted"
        is QuarantineReason.AlreadyClaimed -> "already_claimed"
        is QuarantineReason.KeyAlreadyBound -> "key_already_bound"
        is QuarantineReason.RemovalBlocked -> "removal_blocked"
    }

    private fun QuarantineReason.arg(): String? = when (this) {
        is QuarantineReason.UnknownMember -> memberId.value
        is QuarantineReason.UnknownExpense -> expenseId.value
        is QuarantineReason.UnknownSettlement -> settlementId.value
        is QuarantineReason.UnauthorizedActor -> key.hex
        is QuarantineReason.UnknownInvite -> inviteId.value
        is QuarantineReason.InviteRevoked -> inviteId.value
        is QuarantineReason.InviteExpired -> inviteId.value
        is QuarantineReason.InviteExhausted -> inviteId.value
        is QuarantineReason.AlreadyClaimed -> memberId.value
        is QuarantineReason.KeyAlreadyBound -> key.hex
        is QuarantineReason.RemovalBlocked -> memberId.value
        else -> null
    }

    @Suppress("CyclomaticComplexMethod") // one branch per reason tag; a table would obscure it
    private fun reasonOf(tag: String, arg: String?): QuarantineReason {
        fun need(): String = checkNotNull(arg) { "quarantine reason '$tag' requires an argument" }
        return when (tag) {
            "unknown_member" -> QuarantineReason.UnknownMember(MemberId(need()))
            "unknown_expense" -> QuarantineReason.UnknownExpense(ExpenseId(need()))
            "unknown_settlement" -> QuarantineReason.UnknownSettlement(SettlementId(need()))
            "unknown_group" -> QuarantineReason.UnknownGroup
            "invalid_amounts" -> QuarantineReason.InvalidAmounts
            "duplicate_entity" -> QuarantineReason.DuplicateEntity
            "invalid_signature" -> QuarantineReason.InvalidSignature
            "unauthorized_actor" -> QuarantineReason.UnauthorizedActor(IdentityKey(need()))
            "unknown_invite" -> QuarantineReason.UnknownInvite(InviteId(need()))
            "invite_revoked" -> QuarantineReason.InviteRevoked(InviteId(need()))
            "invite_expired" -> QuarantineReason.InviteExpired(InviteId(need()))
            "invite_exhausted" -> QuarantineReason.InviteExhausted(InviteId(need()))
            "already_claimed" -> QuarantineReason.AlreadyClaimed(MemberId(need()))
            "key_already_bound" -> QuarantineReason.KeyAlreadyBound(IdentityKey(need()))
            "removal_blocked" -> QuarantineReason.RemovalBlocked(MemberId(need()))
            else -> error("unknown quarantine reason tag '$tag'")
        }
    }
}
