// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.importer

import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.LedgerTestFixtures
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class CsvExportTest : FunSpec({

    test("given a group with expenses and a settlement then the export re-imports with identical nets — the round trip") {
        val device = LedgerTestFixtures.SimDevice(deviceA)
        device.act(addMember(alice, "Alice"))
        device.act(addMember(bob, "Bob"))
        device.act(
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Dinner, with commas",
                    currency = CurrencyCode("USD"),
                    total = Money(3000),
                    paidBy = mapOf(alice to Money(3000)),
                    owedBy = mapOf(alice to Money(1500), bob to Money(1500)),
                    dateEpochDay = 20_000,
                    category = "food",
                ),
            ),
        )
        device.act(
            OpPayload.RecordSettlement(SettlementId("s1"), bob, alice, Money(500), CurrencyCode("USD")),
        )
        val state = device.state()

        val csv = CsvExport.export(state)
        val parsed = SplitwiseCsv.parse(csv).shouldBeInstanceOf<SplitwiseCsv.Result.Success>()

        parsed.memberNames shouldBe listOf("Alice", "Bob")
        parsed.skipped shouldBe emptyList()
        parsed.rows.size shouldBe 2

        // Net effect must match the original balances to the cent.
        val expense = parsed.rows.filterIsInstance<SplitwiseCsv.Row.Expense>().single()
        val payment = parsed.rows.filterIsInstance<SplitwiseCsv.Row.Settlement>().single()
        val aliceNet = (expense.paidByMinor["Alice"] ?: 0L) - (expense.owedByMinor["Alice"] ?: 0L) -
            payment.amountMinor
        val originalAliceNet = state.balances().getValue(CurrencyCode("USD"))
            .first { it.memberId == alice }.net.minorUnits
        aliceNet shouldBe originalAliceNet
    }

    test("given a zero-decimal currency then export renders whole units the parser reads back exactly") {
        val device = LedgerTestFixtures.SimDevice(deviceA)
        device.act(addMember(alice, "Alice"))
        device.act(addMember(bob, "Bob"))
        device.act(
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Ramen",
                    currency = CurrencyCode("JPY"),
                    total = Money(1000),
                    paidBy = mapOf(alice to Money(1000)),
                    owedBy = mapOf(alice to Money(500), bob to Money(500)),
                    dateEpochDay = 20_000,
                ),
            ),
        )

        val parsed = SplitwiseCsv.parse(CsvExport.export(device.state()))
            .shouldBeInstanceOf<SplitwiseCsv.Result.Success>()
        val row = parsed.rows.single().shouldBeInstanceOf<SplitwiseCsv.Row.Expense>()
        row.totalMinor shouldBe 1000L
        row.owedByMinor["Bob"] shouldBe 500L
    }
})
