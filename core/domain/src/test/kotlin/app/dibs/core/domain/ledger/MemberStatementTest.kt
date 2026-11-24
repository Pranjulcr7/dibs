// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/** FR-S5: a member's totals, drillable to the expenses behind them (SPEC §1: never an unverifiable number). */
class MemberStatementTest : StringSpec({

    val group = GroupId("11111111-0000-4000-8000-000000000000")
    val members = (1..4).map { MemberId("0%d000000-0000-4000-8000-000000000000".format(it)) }
    val (alice, bob, cara) = members
    val usd = CurrencyCode("USD")
    val jpy = CurrencyCode("JPY")

    fun ledger() = GroupLedger(group, DeviceId("aa")).apply {
        members.forEachIndexed { i, id -> append(OpPayload.AddMember(id, "M$i"), i.toLong()) }
    }

    fun GroupLedger.expense(
        id: String,
        currency: CurrencyCode,
        paid: Map<MemberId, Long>,
        owed: Map<MemberId, Long>,
        day: Long = 20_000,
    ) = append(
        OpPayload.AddExpense(
            ExpenseId(id),
            ExpenseFields(
                description = id,
                currency = currency,
                total = Money(paid.values.sum()),
                paidBy = paid.mapValues { Money(it.value) },
                owedBy = owed.mapValues { Money(it.value) },
                dateEpochDay = day,
            ),
        ),
        100,
    )

    "given expenses and a repayment then the statement shows paid, share, payments and the net they add up to" {
        val l = ledger()
        l.expense("dinner", usd, paid = mapOf(alice to 3000), owed = mapOf(alice to 1000, bob to 1000, cara to 1000), day = 20_001)
        l.expense("taxi", usd, paid = mapOf(bob to 900), owed = mapOf(alice to 450, bob to 450), day = 20_002)
        l.append(OpPayload.RecordSettlement(SettlementId("s1"), bob, alice, Money(300), usd), 200)

        val statement = MemberStatement.of(l.state(), bob).single()

        statement.currency shouldBe usd
        statement.paid shouldBe Money(900)
        statement.share shouldBe Money(1450)
        statement.paymentsMade shouldBe Money(300)
        statement.paymentsReceived shouldBe Money.ZERO
        // 900 − 1450 + 300 − 0
        statement.net shouldBe Money(-250)
        // Newest first, each line showing this member's part only.
        statement.lines.map { Triple(it.description, it.paid, it.share) } shouldBe listOf(
            Triple("taxi", Money(900), Money(450)),
            Triple("dinner", Money.ZERO, Money(1000)),
        )
    }

    "given a member in two currencies then each currency has its own statement and nothing is summed across" {
        val l = ledger()
        l.expense("dinner", usd, paid = mapOf(alice to 2000), owed = mapOf(alice to 1000, bob to 1000))
        l.expense("ramen", jpy, paid = mapOf(bob to 3000), owed = mapOf(alice to 1500, bob to 1500))

        val statements = MemberStatement.of(l.state(), bob)

        statements.map { it.currency } shouldBe listOf(jpy, usd)
        statements.map { it.net } shouldBe listOf(Money(1500), Money(-1000))
    }

    "given deleted expenses and expenses the member is not part of then neither appears" {
        val l = ledger()
        l.expense("theirs", usd, paid = mapOf(alice to 1000), owed = mapOf(alice to 500, cara to 500))
        l.expense("gone", usd, paid = mapOf(bob to 1000), owed = mapOf(alice to 500, bob to 500))
        l.append(OpPayload.DeleteExpense(ExpenseId("gone")), 300)

        MemberStatement.of(l.state(), bob).shouldBeEmpty()
    }

    "given a zero share on an expense then the line is kept, because the member was on it" {
        val l = ledger()
        l.expense("gift", usd, paid = mapOf(alice to 1000), owed = mapOf(alice to 1000, bob to 0))

        MemberStatement.of(l.state(), bob).single().lines.single().share shouldBe Money.ZERO
    }

    "given any ledger then every member's statement net equals their balance, in every currency" {
        // The drill-down and the balances list are two views of one number; if
        // they could disagree, neither could be trusted.
        checkAll(
            300,
            Arb.list(Arb.long(1L, 500_000L), 1..12),
            Arb.list(Arb.int(0, 3), 12..12),
            Arb.list(Arb.long(0L, 50_000L), 0..4),
        ) { totals, payers, settlements ->
            val l = ledger()
            totals.forEachIndexed { i, total ->
                val payer = members[payers[i]]
                val currency = if (i % 3 == 0) jpy else usd
                // Two payers on some expenses; uneven shares, always summing to the total.
                val paid = if (i % 4 == 0 && total > 1) {
                    mapOf(payer to total - total / 3, members[(payers[i] + 1) % 4] to total / 3)
                } else {
                    mapOf(payer to total)
                }
                val first = total / 2
                l.expense("e$i", currency, paid, mapOf(members[i % 4] to first, members[(i + 1) % 4] to total - first))
            }
            settlements.forEachIndexed { i, amount ->
                if (amount > 0) {
                    l.append(
                        OpPayload.RecordSettlement(
                            SettlementId("s$i"),
                            members[i % 4],
                            members[(i + 2) % 4],
                            Money(amount),
                            usd,
                        ),
                        500,
                    )
                }
            }
            val state = l.state()
            val balances = state.balances()
            members.forEach { member ->
                val statements = MemberStatement.of(state, member).associateBy { it.currency }
                balances.forEach { (currency, list) ->
                    val net = list.firstOrNull { it.memberId == member }?.net ?: Money.ZERO
                    (statements[currency]?.net ?: Money.ZERO) shouldBe net
                }
            }
        }
    }
})
