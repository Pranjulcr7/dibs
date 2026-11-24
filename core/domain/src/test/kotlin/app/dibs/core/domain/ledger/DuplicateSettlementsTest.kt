// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Edge 32: two people record the same repayment; both are kept, and the screen hints at it. */
class DuplicateSettlementsTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val usd = CurrencyCode("USD")
    private val first = SettlementId("5a000000-0000-4000-8000-000000000001")
    private val second = SettlementId("5b000000-0000-4000-8000-000000000002")

    private fun ledgerWithDebt(owed: Long): GroupLedger = GroupLedger(group, DeviceId("aa")).apply {
        append(OpPayload.AddMember(alice, "Alice"), 1)
        append(OpPayload.AddMember(bob, "Bob"), 2)
        append(
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Dinner",
                    currency = usd,
                    total = Money(owed * 2),
                    paidBy = mapOf(alice to Money(owed * 2)),
                    owedBy = mapOf(alice to Money(owed), bob to Money(owed)),
                    dateEpochDay = 20_000,
                ),
            ),
            3,
        )
    }

    private fun GroupLedger.pay(id: SettlementId, amount: Long, kind: String? = null) =
        append(OpPayload.RecordSettlement(id, bob, alice, Money(amount), usd, kind), 10)

    @Test
    fun `given the same repayment recorded twice so the payer is now in credit then both are flagged`() {
        val ledger = ledgerWithDebt(1500)
        ledger.pay(first, 1500)
        ledger.pay(second, 1500)

        assertEquals(setOf(first, second), DuplicateSettlements.suspects(ledger.state()))
    }

    @Test
    fun `given two identical payments that were both really owed then nothing is flagged`() {
        // Rent paid in two equal instalments: same people, same amount, no overpayment.
        val ledger = ledgerWithDebt(3000)
        ledger.pay(first, 1500)
        ledger.pay(second, 1500)

        assertTrue(DuplicateSettlements.suspects(ledger.state()).isEmpty())
    }

    @Test
    fun `given an overpayment made of different amounts then nothing is flagged`() {
        val ledger = ledgerWithDebt(1500)
        ledger.pay(first, 1500)
        ledger.pay(second, 700)

        assertTrue(DuplicateSettlements.suspects(ledger.state()).isEmpty())
    }

    @Test
    fun `given a payment and an identical write-off then nothing is flagged`() {
        // A write-off is a deliberate adjustment (edge 14), never an accidental double entry.
        val ledger = ledgerWithDebt(1500)
        ledger.pay(first, 1500)
        ledger.pay(second, 1500, kind = "writeoff")

        assertTrue(DuplicateSettlements.suspects(ledger.state()).isEmpty())
    }

    @Test
    fun `given a single payment then nothing is flagged`() {
        val ledger = ledgerWithDebt(1500)
        ledger.pay(first, 1500)

        assertTrue(DuplicateSettlements.suspects(ledger.state()).isEmpty())
    }
}
