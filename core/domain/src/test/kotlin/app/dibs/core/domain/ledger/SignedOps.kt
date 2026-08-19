// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKeyPair

/** Test shorthand over the production [SignedPayloads] builders. */
object SignedOps {

    fun keyPair(seedByte: Int): IdentityKeyPair = Identity.keyPairFromMaster(ByteArray(32) { seedByte.toByte() })

    fun createInvite(
        groupId: app.dibs.core.domain.model.GroupId,
        inviteId: app.dibs.core.domain.model.InviteId,
        inviter: IdentityKeyPair,
        maxUses: Int = 1,
        expiresAtMillis: Long = 10_000_000_000_000,
    ) = SignedPayloads.createInvite(groupId, inviteId, inviter, maxUses, expiresAtMillis)

    fun revokeInvite(
        groupId: app.dibs.core.domain.model.GroupId,
        inviteId: app.dibs.core.domain.model.InviteId,
        revoker: IdentityKeyPair,
    ) = SignedPayloads.revokeInvite(groupId, inviteId, revoker)

    fun claimMember(
        groupId: app.dibs.core.domain.model.GroupId,
        memberId: app.dibs.core.domain.model.MemberId,
        claimant: IdentityKeyPair,
        displayName: String? = null,
        inviteId: app.dibs.core.domain.model.InviteId? = null,
    ) = SignedPayloads.claimMember(groupId, memberId, claimant, displayName, inviteId)

    fun approveMember(
        groupId: app.dibs.core.domain.model.GroupId,
        memberId: app.dibs.core.domain.model.MemberId,
        approver: IdentityKeyPair,
    ) = SignedPayloads.approveMember(groupId, memberId, approver)

    fun removeMember(
        groupId: app.dibs.core.domain.model.GroupId,
        memberId: app.dibs.core.domain.model.MemberId,
        remover: IdentityKeyPair,
    ) = SignedPayloads.removeMember(groupId, memberId, remover)

    fun setJoinPolicy(
        groupId: app.dibs.core.domain.model.GroupId,
        requiresApproval: Boolean,
        signer: IdentityKeyPair,
    ) = SignedPayloads.setJoinPolicy(groupId, requiresApproval, signer)
}
