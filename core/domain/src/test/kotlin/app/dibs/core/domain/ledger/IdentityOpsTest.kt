// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.SimDevice
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import app.dibs.core.domain.ledger.LedgerTestFixtures.expenseId
import app.dibs.core.domain.ledger.LedgerTestFixtures.group
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** SPEC §5.9: signed identity/join ops, validated deterministically in the fold. */
class IdentityOpsTest : FunSpec({

    val creatorKeys = SignedOps.keyPair(1)
    val joinerKeys = SignedOps.keyPair(2)
    val strangerKeys = SignedOps.keyPair(3)
    val invite1 = InviteId("11110000-0000-4000-8000-000000000001")
    val newMember = MemberId("0e000000-0000-4000-8000-000000000005")

    /** A device whose creator member (alice) is already claimed via bootstrap. */
    fun bootstrappedDevice(): SimDevice {
        val device = SimDevice(deviceA)
        device.act(addMember(alice, "Alice"))
        device.act(SignedOps.claimMember(group, alice, creatorKeys))
        return device
    }

    test("given the group creator when they claim their member without an invite then the bootstrap claim binds the key") {
        val device = bootstrappedDevice()
        val member = device.state().members.getValue(alice)
        member.identityKey shouldBe creatorKeys.publicKey
        member.pending shouldBe false
        device.state().quarantined.isEmpty() shouldBe true
    }

    test("given a claimed group when a second inviteless claim arrives then it is quarantined as unauthorized") {
        val device = bootstrappedDevice()
        device.act(addMember(bob, "Bob"))
        device.act(SignedOps.claimMember(group, bob, joinerKeys))
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnauthorizedActor>()
    }

    test("given a valid invite when a joiner claims an existing unclaimed member then the claim binds and records the invite") {
        val device = bootstrappedDevice()
        device.act(addMember(bob, "Bob"))
        device.act(SignedOps.createInvite(group, invite1, creatorKeys))
        device.act(SignedOps.claimMember(group, bob, joinerKeys, inviteId = invite1))

        val member = device.state().members.getValue(bob)
        member.identityKey shouldBe joinerKeys.publicKey
        member.claimedViaInvite shouldBe invite1
        device.state().invites.getValue(invite1).uses shouldBe 1
        device.state().invites.getValue(invite1).inviterMemberId shouldBe alice
    }

    test("given a valid invite when the joiner creates a new member then the member exists with their chosen name") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, creatorKeys))
        device.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))

        device.state().members.getValue(newMember).name shouldBe "Jo"
        device.state().members.getValue(newMember).identityKey shouldBe joinerKeys.publicKey
    }

    test("given a tampered signature then the op is quarantined as invalid signature") {
        val device = bootstrappedDevice()
        val invite = SignedOps.createInvite(group, invite1, creatorKeys)
        device.act(invite.copy(maxUses = invite.maxUses + 1))
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InvalidSignature>()
    }

    test("given an inviter who is not a claimed member then CreateInvite is quarantined as unauthorized") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, strangerKeys))
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnauthorizedActor>()
    }

    test("given a single-use invite when a second claim reuses it then the reuse is rejected as exhausted") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, creatorKeys, maxUses = 1))
        device.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
        device.act(
            SignedOps.claimMember(
                group,
                MemberId("0f000000-0000-4000-8000-000000000006"),
                strangerKeys,
                displayName = "Sy",
                inviteId = invite1,
            ),
        )

        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InviteExhausted>()
        device.state().members.values.count { it.identityKey != null } shouldBe 2
    }

    test("given an expired invite when a claim arrives stamped after the deadline then it is quarantined as expired") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, creatorKeys, expiresAtMillis = 1_500L))
        device.act(
            SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1),
            wallClockMillis = 2_000L,
        )
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InviteExpired>()
    }

    test("given approval mode when a joiner claims then they are pending until an existing member approves") {
        val device = bootstrappedDevice()
        device.act(SignedOps.setJoinPolicy(group, requiresApproval = true, creatorKeys))
        device.act(SignedOps.createInvite(group, invite1, creatorKeys))
        device.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
        device.state().members.getValue(newMember).pending shouldBe true

        // A pending member cannot approve themselves.
        device.act(SignedOps.approveMember(group, newMember, joinerKeys))
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnauthorizedActor>()

        device.act(SignedOps.approveMember(group, newMember, creatorKeys))
        device.state().members.getValue(newMember).pending shouldBe false
    }

    test("given a member with no history when removed by a claimed member then they disappear as removed") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, creatorKeys))
        device.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
        device.act(SignedOps.removeMember(group, newMember, creatorKeys))

        val member = device.state().members.getValue(newMember)
        member.removed shouldBe true
        member.active shouldBe false
    }

    test("given a member with expense history when removal is attempted then it is blocked, matching edge 13") {
        val device = bootstrappedDevice()
        device.act(addMember(bob, "Bob"))
        device.act(
            LedgerTestFixtures.addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))),
        )
        device.act(SignedOps.removeMember(group, bob, creatorKeys))
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.RemovalBlocked>()
        device.state().members.getValue(bob).removed shouldBe false
    }

    test("given one identity key when it tries to claim a second member then the claim is rejected as key already bound") {
        val device = bootstrappedDevice()
        device.act(SignedOps.createInvite(group, invite1, creatorKeys, maxUses = 5))
        device.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
        device.act(
            SignedOps.claimMember(
                group,
                MemberId("0f000000-0000-4000-8000-000000000006"),
                joinerKeys,
                displayName = "Jo2",
                inviteId = invite1,
            ),
        )
        device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.KeyAlreadyBound>()
    }

    test("race: given two devices claim the same member concurrently then replay order picks one winner, byte-identically in both merge orders") {
        fun run(mergeReversed: Boolean): Pair<ByteArray, GroupState> {
            val a = SimDevice(deviceA)
            a.act(addMember(alice, "Alice"))
            a.act(SignedOps.claimMember(group, alice, creatorKeys))
            a.act(addMember(bob, "Bob"))
            a.act(SignedOps.createInvite(group, invite1, creatorKeys, maxUses = 5))

            val b = SimDevice(deviceB)
            b.receive(a.log.all)

            // Same lamport on both branches: a true concurrent claim of bob.
            val claimA = a.act(SignedOps.claimMember(group, bob, joinerKeys, inviteId = invite1))
            val claimB = b.act(SignedOps.claimMember(group, bob, strangerKeys, inviteId = invite1))
            claimA.lamport shouldBe claimB.lamport

            val c = SimDevice(LedgerTestFixtures.deviceC)
            if (mergeReversed) {
                c.receive(b.log.all)
                c.receive(a.log.all)
            } else {
                c.receive(a.log.all)
                c.receive(b.log.all)
            }
            return c.state().canonicalBytes() to c.state()
        }

        val (bytesForward, state) = run(mergeReversed = false)
        val (bytesReversed, _) = run(mergeReversed = true)
        bytesForward.contentEquals(bytesReversed) shouldBe true

        // deviceA < deviceB, so A's claim folds first and wins; B's quarantines.
        state.members.getValue(bob).identityKey shouldBe joinerKeys.publicKey
        state.quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.AlreadyClaimed>()
    }

    test("race: given a claim racing the invite's revocation then replay order decides, byte-identically in both merge orders") {
        fun run(mergeReversed: Boolean): Pair<ByteArray, GroupState> {
            val a = SimDevice(deviceA)
            a.act(addMember(alice, "Alice"))
            a.act(SignedOps.claimMember(group, alice, creatorKeys))
            a.act(SignedOps.createInvite(group, invite1, creatorKeys))

            val b = SimDevice(deviceB)
            b.receive(a.log.all)

            val revoke = a.act(SignedOps.revokeInvite(group, invite1, creatorKeys))
            val claim = b.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
            revoke.lamport shouldBe claim.lamport

            val c = SimDevice(LedgerTestFixtures.deviceC)
            if (mergeReversed) {
                c.receive(b.log.all)
                c.receive(a.log.all)
            } else {
                c.receive(a.log.all)
                c.receive(b.log.all)
            }
            return c.state().canonicalBytes() to c.state()
        }

        val (bytesForward, state) = run(mergeReversed = false)
        val (bytesReversed, _) = run(mergeReversed = true)
        bytesForward.contentEquals(bytesReversed) shouldBe true

        // deviceA (revoker) < deviceB (claimer): the revoke folds first, so the
        // concurrent claim loses — an invite the inviter killed stays killed.
        state.invites.getValue(invite1).revoked shouldBe true
        state.members.containsKey(newMember) shouldBe false
        state.quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InviteRevoked>()
    }

    test("given a claim that folds before a concurrent revoke then the join stands and the invite still ends revoked") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"))
        a.act(SignedOps.claimMember(group, alice, creatorKeys))
        a.act(SignedOps.createInvite(group, invite1, creatorKeys))

        val b = SimDevice(deviceB)
        b.receive(a.log.all)
        // Claim first in replay order (lower lamport), revoke after.
        b.act(SignedOps.claimMember(group, newMember, joinerKeys, displayName = "Jo", inviteId = invite1))
        a.receive(b.log.all)
        a.act(SignedOps.revokeInvite(group, invite1, creatorKeys))
        b.receive(a.log.all)

        listOf(a, b).forEach { device ->
            device.state().members.getValue(newMember).identityKey.shouldNotBeNull()
            device.state().invites.getValue(invite1).revoked shouldBe true
            device.state().quarantined.isEmpty() shouldBe true
        }
        a.state().canonicalBytes().contentEquals(b.state().canonicalBytes()) shouldBe true
    }
})
