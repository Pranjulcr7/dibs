// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC §5.9 join flow, joiner's side: what the join screen should say *before*
 * a claim is written. The fold stays the authority — these checks only keep a
 * joiner from appending an op that every device would then quarantine.
 */
class JoinCheckTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val invite = InviteId("22222222-0000-4000-8000-000000000000")
    private val inviterKeys = Identity.keyPairFromMaster(ByteArray(32) { 1 })
    private val joinerKeys = Identity.keyPairFromMaster(ByteArray(32) { 2 })
    private val now = 1_700_000_000_000L
    private val expiry = now + 1_000

    private fun inviterLedger(maxUses: Int = 1): GroupLedger = GroupLedger(group, DeviceId("aa")).apply {
        append(OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")), now - 50)
        append(OpPayload.AddMember(alice, "Alice"), now - 40)
        append(OpPayload.AddMember(bob, "Bob"), now - 30)
        append(SignedPayloads.claimMember(group, alice, inviterKeys), now - 20)
        append(SignedPayloads.createInvite(group, invite, inviterKeys, maxUses, expiry), now - 10)
    }

    private fun check(ledger: GroupLedger, at: Long = now) =
        JoinCheck.evaluate(ledger.state(), invite, joinerKeys.publicKey, at)

    @Test
    fun `given a link whose group data has not arrived then the check asks for the group's updates`() {
        val empty = GroupLedger(group, DeviceId("bb"))

        assertEquals(JoinCheck.Result.NeedsUpdates, check(empty))
    }

    @Test
    fun `given a valid unused invite then joining is ready and lists only unclaimed active members to claim`() {
        val ledger = inviterLedger()
        val carol = MemberId("0c000000-0000-4000-8000-000000000003")
        ledger.append(OpPayload.AddMember(carol, "Carol"), now - 5)
        ledger.append(OpPayload.DeactivateMember(carol), now - 4)

        val result = check(ledger) as JoinCheck.Result.Ready

        assertEquals("Trip", result.groupName)
        // Alice is claimed (the inviter), Carol is deactivated: only Bob is on offer.
        assertEquals(listOf(bob), result.claimable.map { it.id })
        assertEquals(false, result.approvalRequired)
        assertEquals("Alice", result.inviterName)
    }

    @Test
    fun `given approval mode then ready says the join will wait for approval`() {
        val ledger = inviterLedger()
        ledger.append(SignedPayloads.setJoinPolicy(group, requiresApproval = true, signer = inviterKeys), now - 5)

        assertTrue((check(ledger) as JoinCheck.Result.Ready).approvalRequired)
    }

    @Test
    fun `given group data that predates the invite then the check says the data is out of date`() {
        val stale = GroupLedger(group, DeviceId("aa")).apply {
            append(OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")), now - 50)
            append(OpPayload.AddMember(alice, "Alice"), now - 40)
        }

        assertEquals(JoinCheck.Result.InviteNotInData, check(stale))
    }

    @Test
    fun `given a revoked invite then the check says so`() {
        val ledger = inviterLedger()
        ledger.append(SignedPayloads.revokeInvite(group, invite, inviterKeys), now - 5)

        assertEquals(JoinCheck.Result.InviteRevoked, check(ledger))
    }

    @Test
    fun `given an invite past its deadline then the check says it expired, and the deadline itself still works`() {
        val ledger = inviterLedger()

        assertTrue(check(ledger, at = expiry) is JoinCheck.Result.Ready)
        assertEquals(JoinCheck.Result.InviteExpired, check(ledger, at = expiry + 1))
    }

    @Test
    fun `given a single-use invite someone else already used then the check says it is used up`() {
        val ledger = inviterLedger()
        val other = Identity.keyPairFromMaster(ByteArray(32) { 3 })
        ledger.append(SignedPayloads.claimMember(group, bob, other, inviteId = invite), now)

        assertEquals(JoinCheck.Result.InviteUsedUp, check(ledger))
    }

    @Test
    fun `given our key is already bound in the group then the check reports the member instead of offering a second join`() {
        val ledger = inviterLedger()
        ledger.append(SignedPayloads.claimMember(group, bob, joinerKeys, inviteId = invite), now)

        assertEquals(JoinCheck.Result.AlreadyMember(bob, pending = false), check(ledger))
    }

    @Test
    fun `given our pending claim under approval mode then the check reports us as a member awaiting approval`() {
        val ledger = inviterLedger()
        ledger.append(SignedPayloads.setJoinPolicy(group, requiresApproval = true, signer = inviterKeys), now - 5)
        ledger.append(SignedPayloads.claimMember(group, bob, joinerKeys, inviteId = invite), now)

        assertEquals(JoinCheck.Result.AlreadyMember(bob, pending = true), check(ledger))
    }

    @Test
    fun `given every ready or refused check then appending the claim agrees with the fold`() {
        // The check must never be more permissive than the fold: whenever it
        // says Ready, a claim written at that moment must actually apply.
        val ledger = inviterLedger()
        assertTrue(check(ledger) is JoinCheck.Result.Ready)

        ledger.append(SignedPayloads.claimMember(group, bob, joinerKeys, inviteId = invite), now)

        assertEquals(joinerKeys.publicKey, ledger.state().members.getValue(bob).identityKey)
        assertTrue(ledger.state().quarantined.isEmpty())
    }
}
