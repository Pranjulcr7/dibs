// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.balance

import app.dibs.core.domain.DomainArbs
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random

class BalancesTest : FunSpec({

    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")
    val c = MemberId("0c000000-0000-4000-8000-000000000003")
    val usd = CurrencyCode("USD")
    val jpy = CurrencyCode("JPY")

    fun expense(id: String, currency: CurrencyCode, paidBy: Map<MemberId, Money>, owedBy: Map<MemberId, Money>) =
        Expense(ExpenseId(id), currency, paidBy, owedBy)

    test("given one expense paid by one member when netted then payer is owed and others owe") {
        val net = Balances.net(
            listOf(expense("e1", usd, mapOf(a to Money(3000)), mapOf(a to Money(1000), b to Money(1000), c to Money(1000)))),
        )
        net.getValue(usd) shouldBe listOf(
            MemberBalance(a, Money(2000)),
            MemberBalance(b, Money(-1000)),
            MemberBalance(c, Money(-1000)),
        )
    }

    test("given multiple payers on one expense when netted then each payer is credited their contribution") {
        val net = Balances.net(
            listOf(expense("e1", usd, mapOf(a to Money(2000), b to Money(1000)), mapOf(a to Money(1000), b to Money(1000), c to Money(1000)))),
        )
        net.getValue(usd) shouldBe listOf(
            MemberBalance(a, Money(1000)),
            MemberBalance(b, Money.ZERO),
            MemberBalance(c, Money(-1000)),
        )
    }

    test("given a settlement payment when netted then it moves the balance from debtor toward zero") {
        val net = Balances.net(
            expenses = listOf(expense("e1", usd, mapOf(a to Money(2000)), mapOf(a to Money(1000), b to Money(1000)))),
            settlements = listOf(SettlementPayment(from = b, to = a, amount = Money(600), currency = usd)),
        )
        net.getValue(usd) shouldBe listOf(
            MemberBalance(a, Money(400)),
            MemberBalance(b, Money(-400)),
        )
    }

    test("given a partial then full settlement when netted then the pair ends settled up") {
        val net = Balances.net(
            expenses = listOf(expense("e1", usd, mapOf(a to Money(1000)), mapOf(b to Money(1000)))),
            settlements = listOf(
                SettlementPayment(from = b, to = a, amount = Money(300), currency = usd),
                SettlementPayment(from = b, to = a, amount = Money(700), currency = usd),
            ),
        )
        net.getValue(usd) shouldBe listOf(
            MemberBalance(a, Money.ZERO),
            MemberBalance(b, Money.ZERO),
        )
    }

    test("given expenses in two currencies when netted then balances are kept per currency and never summed across") {
        val net = Balances.net(
            listOf(
                expense("e1", usd, mapOf(a to Money(1000)), mapOf(b to Money(1000))),
                expense("e2", jpy, mapOf(b to Money(500)), mapOf(a to Money(500))),
            ),
        )
        net.getValue(usd) shouldBe listOf(MemberBalance(a, Money(1000)), MemberBalance(b, Money(-1000)))
        net.getValue(jpy) shouldBe listOf(MemberBalance(a, Money(-500)), MemberBalance(b, Money(500)))
    }

    test("given an expense whose payments do not sum to its shares then construction is rejected") {
        shouldThrow<IllegalArgumentException> {
            expense("e1", usd, mapOf(a to Money(1000)), mapOf(b to Money(900)))
        }
    }

    test("given a non-positive settlement amount then construction is rejected") {
        shouldThrow<IllegalArgumentException> { SettlementPayment(a, b, Money.ZERO, usd) }
        shouldThrow<IllegalArgumentException> { SettlementPayment(a, b, Money(-100), usd) }
    }

    test("given a settlement paid to oneself then construction is rejected") {
        shouldThrow<IllegalArgumentException> { SettlementPayment(a, a, Money(100), usd) }
    }

    test("given 10000 random expense sets with settlements when netted then every currency's balances sum to zero") {
        val arb = io.kotest.property.Arb.bind(
            DomainArbs.expenses,
            io.kotest.property.Arb.int(0..10),
            io.kotest.property.Arb.long(),
        ) { expenses, settlementCount, seed -> Triple(expenses, settlementCount, seed) }
        checkAll(10_000, arb) { (expenses, settlementCount, seed) ->
            val random = Random(seed)
            val members = expenses.flatMap { it.paidBy.keys + it.owedBy.keys }.distinct()
            val currency = expenses.first().currency
            val settlements = if (members.size < 2) {
                emptyList()
            } else {
                DomainArbs.settlements(members, currency, random, settlementCount)
            }
            val net = Balances.net(expenses, settlements)
            net.forEach { (_, balances) ->
                balances.fold(0L) { acc, balance -> Math.addExact(acc, balance.net.minorUnits) } shouldBe 0L
            }
        }
    }
})
