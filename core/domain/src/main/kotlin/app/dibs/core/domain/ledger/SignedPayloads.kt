// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId

/**
 * Builders producing correctly signed identity payloads (SPEC §5.9) — the one
 * place signing and message construction meet, shared by the app's join/create
 * flows, sync (M6), and tests, so signer and verifier can never drift.
 */
object SignedPayloads {

    fun createInvite(
        groupId: GroupId,
        inviteId: InviteId,
        inviter: IdentityKeyPair,
        maxUses: Int = 1,
        expiresAtMillis: Long = 10_000_000_000_000,
    ): OpPayload.CreateInvite = OpPayload.CreateInvite(
        inviteId = inviteId,
        inviterKey = inviter.publicKey,
        maxUses = maxUses,
        expiresAtMillis = expiresAtMillis,
        signature = HexBytes.toHex(
            Identity.sign(
                inviter,
                SignedMessages.createInvite(groupId, inviteId, inviter.publicKey, maxUses, expiresAtMillis),
            ),
        ),
    )

    fun revokeInvite(groupId: GroupId, inviteId: InviteId, revoker: IdentityKeyPair): OpPayload.RevokeInvite =
        OpPayload.RevokeInvite(
            inviteId = inviteId,
            revokerKey = revoker.publicKey,
            signature = HexBytes.toHex(
                Identity.sign(revoker, SignedMessages.revokeInvite(groupId, inviteId, revoker.publicKey)),
            ),
        )

    fun claimMember(
        groupId: GroupId,
        memberId: MemberId,
        claimant: IdentityKeyPair,
        displayName: String? = null,
        inviteId: InviteId? = null,
    ): OpPayload.ClaimMember = OpPayload.ClaimMember(
        memberId = memberId,
        displayName = displayName,
        inviteId = inviteId,
        claimantKey = claimant.publicKey,
        signature = HexBytes.toHex(
            Identity.sign(
                claimant,
                SignedMessages.claimMember(groupId, memberId, displayName, inviteId, claimant.publicKey),
            ),
        ),
    )

    fun approveMember(groupId: GroupId, memberId: MemberId, approver: IdentityKeyPair): OpPayload.ApproveMember =
        OpPayload.ApproveMember(
            memberId = memberId,
            approverKey = approver.publicKey,
            signature = HexBytes.toHex(
                Identity.sign(approver, SignedMessages.approveMember(groupId, memberId, approver.publicKey)),
            ),
        )

    fun removeMember(groupId: GroupId, memberId: MemberId, remover: IdentityKeyPair): OpPayload.RemoveMember =
        OpPayload.RemoveMember(
            memberId = memberId,
            removerKey = remover.publicKey,
            signature = HexBytes.toHex(
                Identity.sign(remover, SignedMessages.removeMember(groupId, memberId, remover.publicKey)),
            ),
        )

    fun setJoinPolicy(groupId: GroupId, requiresApproval: Boolean, signer: IdentityKeyPair): OpPayload.SetJoinPolicy =
        OpPayload.SetJoinPolicy(
            requiresApproval = requiresApproval,
            signerKey = signer.publicKey,
            signature = HexBytes.toHex(
                Identity.sign(signer, SignedMessages.setJoinPolicy(groupId, requiresApproval, signer.publicKey)),
            ),
        )
}
