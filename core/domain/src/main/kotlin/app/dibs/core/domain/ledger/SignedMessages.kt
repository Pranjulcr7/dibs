// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId

/**
 * The exact byte strings identity keys sign (SPEC §5.9). Signer and verifier
 * must agree forever, so these are versioned, length-prefixed like the op
 * canonical encoding (no delimiter injection), and include the groupId so a
 * signature can never be replayed into another group. Changing any of these is
 * a breaking format change — bump the version tag and write a migration.
 */
object SignedMessages {

    @Suppress("LongParameterList") // the five signed fields of an invite
    fun createInvite(
        groupId: GroupId,
        inviteId: InviteId,
        inviterKey: IdentityKey,
        maxUses: Int,
        expiresAtMillis: Long,
    ): ByteArray = build("dibs-invite-v1") {
        str(groupId.value)
        str(inviteId.value)
        str(inviterKey.hex)
        num(maxUses.toLong())
        num(expiresAtMillis)
    }

    fun revokeInvite(groupId: GroupId, inviteId: InviteId, revokerKey: IdentityKey): ByteArray =
        build("dibs-revoke-v1") {
            str(groupId.value)
            str(inviteId.value)
            str(revokerKey.hex)
        }

    fun claimMember(
        groupId: GroupId,
        memberId: MemberId,
        displayName: String?,
        inviteId: InviteId?,
        claimantKey: IdentityKey,
    ): ByteArray = build("dibs-claim-v1") {
        str(groupId.value)
        str(memberId.value)
        opt(displayName)
        opt(inviteId?.value)
        str(claimantKey.hex)
    }

    fun approveMember(groupId: GroupId, memberId: MemberId, approverKey: IdentityKey): ByteArray =
        build("dibs-approve-v1") {
            str(groupId.value)
            str(memberId.value)
            str(approverKey.hex)
        }

    fun removeMember(groupId: GroupId, memberId: MemberId, removerKey: IdentityKey): ByteArray =
        build("dibs-remove-v1") {
            str(groupId.value)
            str(memberId.value)
            str(removerKey.hex)
        }

    fun setJoinPolicy(groupId: GroupId, requiresApproval: Boolean, signerKey: IdentityKey): ByteArray =
        build("dibs-joinpolicy-v1") {
            str(groupId.value)
            str(if (requiresApproval) "1" else "0")
            str(signerKey.hex)
        }

    private class Builder(val sb: StringBuilder) {
        fun str(value: String) {
            sb.append('s').append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(';')
        }

        fun opt(value: String?) {
            if (value == null) sb.append("n;") else str(value)
        }

        fun num(value: Long) {
            sb.append('i').append(value).append(';')
        }
    }

    private fun build(tag: String, block: Builder.() -> Unit): ByteArray {
        val builder = Builder(StringBuilder())
        builder.str(tag)
        builder.block()
        return builder.sb.toString().toByteArray(Charsets.UTF_8)
    }
}
