// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot codec must be lossless over the *entire* state tree — a field
 * silently dropped here would resurface as wrong data on every cold open, so
 * the fixture deliberately exercises every optional branch: rename history,
 * trash, settlement kinds, meta decoration, join policy, archive, identity
 * claims, invites, and a quarantined op.
 */
class GroupStateCodecTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val device = DeviceId("aa000000-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val keyPair = Identity.keyPairFromMaster(ByteArray(32) { 42 })

    private fun richState(): app.dibs.core.domain.ledger.GroupState {
        val ledger = GroupLedger(group, device)
        var clock = 1_000L
        fun next() = ++clock

        ledger.append(
            OpPayload.CreateGroup("Trip", emoji = "🌍", colorArgb = 0xFF336699, defaultCurrency = CurrencyCode("EUR")),
            next(),
        )
        ledger.append(OpPayload.AddMember(alice, "Alice"), next())
        ledger.append(OpPayload.AddMember(bob, "Bob"), next())
        ledger.append(OpPayload.RenameMember(alice, "Alicia"), next())
        ledger.append(SignedPayloads.claimMember(group, alice, keyPair, displayName = "Alicia"), next())
        ledger.append(SignedPayloads.createInvite(group, InviteId("inv-1"), keyPair, maxUses = 3), next())
        ledger.append(SignedPayloads.setJoinPolicy(group, requiresApproval = true, signer = keyPair), next())
        ledger.append(OpPayload.SetArchived(false), next())
        ledger.append(
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Dinner",
                    currency = CurrencyCode("EUR"),
                    total = Money(3000),
                    paidBy = mapOf(alice to Money(3000)),
                    owedBy = mapOf(alice to Money(1500), bob to Money(1500)),
                    dateEpochDay = 20_100,
                    note = "seaside",
                    category = "food",
                    receiptRef = "abc123.jpg",
                    splitMode = "equal",
                ),
            ),
            next(),
        )
        ledger.append(OpPayload.DeleteExpense(ExpenseId("e1")), next())
        ledger.append(OpPayload.RestoreExpense(ExpenseId("e1")), next())
        ledger.append(
            OpPayload.AddExpense(
                ExpenseId("e2"),
                ExpenseFields(
                    description = "Trashed",
                    currency = CurrencyCode("EUR"),
                    total = Money(500),
                    paidBy = mapOf(bob to Money(500)),
                    owedBy = mapOf(alice to Money(500)),
                    dateEpochDay = 20_101,
                ),
            ),
            next(),
        )
        ledger.append(OpPayload.DeleteExpense(ExpenseId("e2")), next())
        ledger.append(
            OpPayload.RecordSettlement(SettlementId("s1"), bob, alice, Money(700), CurrencyCode("EUR")),
            next(),
        )
        ledger.append(
            OpPayload.RecordSettlement(
                SettlementId("s2"),
                alice,
                bob,
                Money(100),
                CurrencyCode("EUR"),
                kind = "writeoff",
            ),
            next(),
        )
        // Quarantined: expense referencing a member this group has never seen.
        ledger.append(
            OpPayload.AddExpense(
                ExpenseId("e3"),
                ExpenseFields(
                    description = "orphan",
                    currency = CurrencyCode("EUR"),
                    total = Money(100),
                    paidBy = mapOf(MemberId("0c000000-0000-4000-8000-00000000000f") to Money(100)),
                    owedBy = mapOf(MemberId("0c000000-0000-4000-8000-00000000000f") to Money(100)),
                    dateEpochDay = 20_102,
                ),
            ),
            next(),
        )
        return ledger.state()
    }

    @Test
    fun `given a state exercising every branch then the codec roundtrips it losslessly`() {
        val original = richState()
        // Sanity: the fixture really covers the interesting branches.
        assertTrue(original.quarantined.isNotEmpty())
        assertTrue(original.invites.isNotEmpty())
        assertTrue(original.trash.isNotEmpty())
        assertTrue(original.members.getValue(alice).nameHistory.isNotEmpty())
        assertTrue(original.members.getValue(alice).identityKey != null)

        val decoded = GroupStateCodec.decode(group, GroupStateCodec.encode(original))

        assertEquals(original, decoded)
        assertArrayEquals(original.canonicalBytes(), decoded.canonicalBytes())
    }

    @Test
    fun `given an empty state then the codec roundtrips it`() {
        val original = app.dibs.core.domain.ledger.GroupState.empty(group)
        assertEquals(original, GroupStateCodec.decode(group, GroupStateCodec.encode(original)))
    }
}
