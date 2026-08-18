// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.settle

import app.dibs.core.domain.DomainArbs
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

class SettlementTest : FunSpec({

    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")
    val c = MemberId("0c000000-0000-4000-8000-000000000003")

    test("given one debtor and one creditor when simplified then a single transfer settles them") {
        val transfers = Settlement.simplify(
            listOf(MemberBalance(a, Money(1000)), MemberBalance(b, Money(-1000))),
        )
        transfers shouldBe listOf(Transfer(from = b, to = a, amount = Money(1000)))
    }

    test("given two equal creditors when simplified then the creditor tie breaks by ascending member UUID") {
        val transfers = Settlement.simplify(
            listOf(MemberBalance(a, Money(1000)), MemberBalance(b, Money(1000)), MemberBalance(c, Money(-2000))),
        )
        transfers shouldBe listOf(
            Transfer(from = c, to = a, amount = Money(1000)),
            Transfer(from = c, to = b, amount = Money(1000)),
        )
    }

    test("given a chain of debts when simplified then transfers never exceed member count minus one") {
        val transfers = Settlement.simplify(
            listOf(MemberBalance(a, Money(500)), MemberBalance(b, Money(2500)), MemberBalance(c, Money(-3000))),
        )
        transfers.size shouldBe 2
        transfers shouldBe listOf(
            Transfer(from = c, to = b, amount = Money(2500)),
            Transfer(from = c, to = a, amount = Money(500)),
        )
    }

    test("given everyone settled up when simplified then no transfers are suggested") {
        Settlement.simplify(
            listOf(MemberBalance(a, Money.ZERO), MemberBalance(b, Money.ZERO)),
        ).shouldBeEmpty()
    }

    test("given a single-member group when simplified then no transfers are suggested") {
        Settlement.simplify(listOf(MemberBalance(a, Money.ZERO))).shouldBeEmpty()
    }

    test("given balances that do not sum to zero when simplified then input is rejected") {
        shouldThrow<IllegalArgumentException> {
            Settlement.simplify(listOf(MemberBalance(a, Money(100))))
        }
    }

    test("given 10000 random zero-sum balances when simplified then settlement terminates in at most n minus one transfers") {
        checkAll(10_000, DomainArbs.zeroSumBalances) { balances ->
            val transfers = Settlement.simplify(balances)
            val nonZero = balances.count { !it.net.isZero }
            (transfers.size <= maxOf(nonZero - 1, 0)) shouldBe true
        }
    }

    test("given 10000 random zero-sum balances when transfers applied then every member's balance reaches exactly zero") {
        checkAll(10_000, DomainArbs.zeroSumBalances) { balances ->
            val transfers = Settlement.simplify(balances)
            val remaining = balances.associate { it.memberId to it.net.minorUnits }.toMutableMap()
            transfers.forEach { transfer ->
                (transfer.amount.minorUnits > 0) shouldBe true
                remaining[transfer.from] = remaining.getValue(transfer.from) + transfer.amount.minorUnits
                remaining[transfer.to] = remaining.getValue(transfer.to) - transfer.amount.minorUnits
            }
            remaining.values.forEach { it shouldBe 0L }
        }
    }
})
