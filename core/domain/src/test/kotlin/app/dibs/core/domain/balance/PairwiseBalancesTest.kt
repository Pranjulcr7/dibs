// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.balance

import app.dibs.core.domain.DomainArbs
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

class PairwiseBalancesTest : FunSpec({

    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")
    val c = MemberId("0c000000-0000-4000-8000-000000000003")
    val usd = CurrencyCode("USD")

    fun expense(id: String, paidBy: Map<MemberId, Money>, owedBy: Map<MemberId, Money>) =
        Expense(ExpenseId(id), usd, paidBy, owedBy)

    test("given a single-payer expense then each participant directly owes the payer exactly their share") {
        val pairs = PairwiseBalances.compute(
            listOf(expense("e1", mapOf(a to Money(1000)), mapOf(a to Money(334), b to Money(333), c to Money(333)))),
        ).getValue(usd)

        pairs shouldBe listOf(
            PairDebt(from = b, to = a, amount = Money(333)),
            PairDebt(from = c, to = a, amount = Money(333)),
        )
    }

    test("given opposing debts across expenses then the pair nets to a single direction") {
        val pairs = PairwiseBalances.compute(
            listOf(
                expense("e1", mapOf(a to Money(500)), mapOf(b to Money(500))),
                expense("e2", mapOf(b to Money(200)), mapOf(a to Money(200))),
            ),
        ).getValue(usd)

        pairs shouldBe listOf(PairDebt(from = b, to = a, amount = Money(300)))
    }

    test("given a settlement then it reduces that pair's debt directly") {
        val pairs = PairwiseBalances.compute(
            expenses = listOf(expense("e1", mapOf(a to Money(1000)), mapOf(b to Money(1000)))),
            settlements = listOf(SettlementPayment(from = b, to = a, amount = Money(400), currency = usd)),
        ).getValue(usd)

        pairs shouldBe listOf(PairDebt(from = b, to = a, amount = Money(600)))
    }

    test("given a fully settled pair then it disappears from the list") {
        val pairs = PairwiseBalances.compute(
            expenses = listOf(expense("e1", mapOf(a to Money(1000)), mapOf(b to Money(1000)))),
            settlements = listOf(SettlementPayment(from = b, to = a, amount = Money(1000), currency = usd)),
        )
        (pairs[usd] ?: emptyList()) shouldBe emptyList<PairDebt>()
    }

    test("given 2000 random multi-payer ledgers then every member's pairwise net equals their overall net balance") {
        checkAll(2000, DomainArbs.expenses) { expenses ->
            val nets = Balances.net(expenses)
            val pairs = PairwiseBalances.compute(expenses)
            nets.forEach { (currency, memberBalances) ->
                val pairList = pairs[currency] ?: emptyList()
                memberBalances.forEach { balance ->
                    val owedToMe = pairList.filter { it.to == balance.memberId }.sumOf { it.amount.minorUnits }
                    val iOwe = pairList.filter { it.from == balance.memberId }.sumOf { it.amount.minorUnits }
                    (owedToMe - iOwe) shouldBe balance.net.minorUnits
                }
            }
        }
    }

    test("given the same ledger twice then the pair list is byte-for-byte deterministic and sorted") {
        val expenses = listOf(
            expense("e1", mapOf(a to Money(700), b to Money(300)), mapOf(a to Money(100), b to Money(400), c to Money(500))),
        )
        val first = PairwiseBalances.compute(expenses)
        val second = PairwiseBalances.compute(expenses)
        first shouldBe second
        first.getValue(usd) shouldBe first.getValue(usd).sortedWith(compareBy({ it.from }, { it.to }))
    }
})
