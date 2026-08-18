// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.domain.ledger.AmountsEdit
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseEdit
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.NoteEdit
import app.dibs.core.domain.ledger.OpFactory
import app.dibs.core.domain.ledger.OpId
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class OpCodecTest {

    private val group = GroupId("g1000000-0000-4000-8000-000000000000")
    private val device = DeviceId("d1000000-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    private val everyPayloadKind: List<OpPayload> = listOf(
        OpPayload.AddMember(alice, "Alice"),
        OpPayload.RenameMember(alice, "Alicia | with ; odd = chars"),
        OpPayload.DeactivateMember(bob),
        OpPayload.AddExpense(
            ExpenseId("e1"),
            ExpenseFields(
                description = "dinner",
                currency = CurrencyCode("KWD"),
                total = Money(10_000),
                paidBy = mapOf(alice to Money(10_000)),
                owedBy = mapOf(alice to Money(5_000), bob to Money(5_000)),
                dateEpochDay = 20_500,
                note = "with unicode ✓ and \"quotes\"",
            ),
        ),
        OpPayload.EditExpense(ExpenseId("e1"), ExpenseEdit(description = "renamed")),
        OpPayload.EditExpense(ExpenseId("e1"), ExpenseEdit(note = NoteEdit(null))),
        OpPayload.EditExpense(
            ExpenseId("e1"),
            ExpenseEdit(
                amounts = AmountsEdit(
                    currency = CurrencyCode("JPY"),
                    total = Money(3000),
                    paidBy = mapOf(bob to Money(3000)),
                    owedBy = mapOf(alice to Money(1500), bob to Money(1500)),
                ),
            ),
        ),
        OpPayload.DeleteExpense(ExpenseId("e1")),
        OpPayload.RestoreExpense(ExpenseId("e1")),
        OpPayload.RecordSettlement(SettlementId("s1"), bob, alice, Money(100), CurrencyCode("USD")),
    )

    @Test
    fun `given every payload kind when encoded to an entity and decoded back then the op round-trips exactly`() {
        everyPayloadKind.forEachIndexed { index, payload ->
            val op = OpFactory.create(
                groupId = group,
                deviceId = device,
                lamport = index + 1L,
                parents = if (index == 0) emptySet() else setOf(OpId("f".repeat(64))),
                wallClockMillis = 1_700_000_000_000 + index,
                payload = payload,
            )
            val decoded = OpCodec.fromEntity(OpCodec.toEntity(op))
            assertEquals("payload kind #$index did not round-trip", op, decoded)
        }
    }

    @Test
    fun `given a decoded op when its id is recomputed from content then it matches the stored id`() {
        // Storage must never be able to smuggle a tampered op past the
        // content-address: decode recomputes and verifies.
        val op = OpFactory.create(group, device, 1, emptySet(), 42, OpPayload.AddMember(alice, "Alice"))
        val decoded = OpCodec.fromEntity(OpCodec.toEntity(op))
        assertEquals(
            op.id,
            OpFactory.computeId(
                decoded.groupId,
                decoded.deviceId,
                decoded.lamport,
                decoded.parents,
                decoded.wallClockMillis,
                decoded.payload,
            ),
        )
    }
}
