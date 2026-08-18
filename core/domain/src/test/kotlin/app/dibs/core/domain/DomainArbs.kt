// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain

import app.dibs.core.domain.balance.Expense
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.balance.SettlementPayment
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.split.LineItem
import app.dibs.core.domain.split.SplitSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import java.util.UUID
import kotlin.random.Random

/**
 * Generators for property tests. Everything is derived from Kotest's seeded
 * [io.kotest.property.RandomSource] so failures are reproducible.
 */
object DomainArbs {

    /** Mix of 0-, 2-, and 3-decimal currencies so no property is USD-only. */
    val currency: Arb<CurrencyCode> = Arb.of(
        listOf("USD", "EUR", "GBP", "INR", "JPY", "KRW", "KWD", "BHD", "TND").map(::CurrencyCode),
    )

    val memberId: Arb<MemberId> = Arb.bind(Arb.long(), Arb.long()) { hi, lo ->
        MemberId(UUID(hi, lo).toString())
    }

    fun members(sizes: IntRange = 1..20): Arb<List<MemberId>> =
        Arb.int(sizes).flatMap { n ->
            arbitrary { rs ->
                List(n) { MemberId(UUID(rs.random.nextLong(), rs.random.nextLong()).toString()) }
            }
        }

    val total: Arb<Money> = Arb.long(0L..1_000_000_000_000L).map(::Money)

    val equalSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(total, members()) { t, m -> t to SplitSpec.Equal(m.toSet()) }

    val sharesSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(total, members()) { t, m -> t to SplitSpec.ByShares(m.withRandomWeights(t.minorUnits)) }

    val percentageSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(total, members()) { t, m ->
            t to SplitSpec.ByPercentage(composition(m, 10_000L, Random(t.minorUnits xor m.size.toLong())))
        }

    val exactSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(total, members()) { t, m ->
            t to SplitSpec.ByExactAmounts(
                composition(m, t.minorUnits, Random(t.minorUnits + m.size)).mapValues { Money(it.value) },
            )
        }

    /** Non-negative deltas that never exceed the total, so the spec is always valid. */
    val adjustmentSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(total, members()) { t, m ->
            val random = Random(t.minorUnits - m.size)
            var remaining = t.minorUnits
            val deltas = m.associateWith { member ->
                val d = if (remaining == 0L) 0L else random.nextLong(0, remaining / 2 + 1)
                remaining -= d
                Money(d)
            }
            t to SplitSpec.ByAdjustment(m.toSet(), deltas)
        }

    val itemizedSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.bind(members(1..8), Arb.int(1..10), Arb.long(0..1_000_000L), Arb.long(0..1_000_000L), Arb.long()) {
                m, itemCount, taxUnits, tipUnits, seed ->
            val random = Random(seed)
            val items = List(itemCount) {
                val consumers = m.shuffled(random).take(random.nextInt(1, m.size + 1))
                LineItem(
                    price = Money(random.nextLong(0, 10_000_000L)),
                    consumers = consumers.associateWith { random.nextLong(1, 6) },
                )
            }
            val itemSum = items.sumOf { it.price.minorUnits }
            val tax = Money(taxUnits)
            val tip = Money(tipUnits)
            Money(itemSum + taxUnits + tipUnits) to SplitSpec.Itemized(items, tax, tip)
        }

    /** Every split mode, for cross-mode properties like sum invariance. */
    val anyValidSpec: Arb<Pair<Money, SplitSpec>> =
        Arb.choice(equalSpec, sharesSpec, percentageSpec, exactSpec, adjustmentSpec, itemizedSpec)

    /** Balances that sum to zero by construction, as domain code guarantees. */
    val zeroSumBalances: Arb<List<MemberBalance>> =
        Arb.bind(members(2..20), Arb.long()) { m, seed ->
            val random = Random(seed)
            var sum = 0L
            val nets = m.dropLast(1).map { member ->
                val net = random.nextLong(-1_000_000_000L, 1_000_000_001L)
                sum += net
                MemberBalance(member, Money(net))
            }
            nets + MemberBalance(m.last(), Money(-sum))
        }

    val expenses: Arb<List<Expense>> =
        Arb.bind(members(2..10), currency, Arb.int(1..30), Arb.long()) { m, ccy, count, seed ->
            val random = Random(seed)
            List(count) { randomExpense(m, ccy, random) }
        }

    fun settlements(members: List<MemberId>, currency: CurrencyCode, random: Random, count: Int): List<SettlementPayment> =
        List(count) {
            val from = members.random(random)
            val to = (members - from).random(random)
            SettlementPayment(from, to, Money(random.nextLong(1, 100_000L)), currency)
        }

    fun randomExpense(members: List<MemberId>, currency: CurrencyCode, random: Random): Expense {
        val total = random.nextLong(0, 1_000_000L)
        val participants = members.shuffled(random).take(random.nextInt(1, members.size + 1))
        val payers = members.shuffled(random).take(random.nextInt(1, 3))
        return Expense(
            id = ExpenseId(UUID(random.nextLong(), random.nextLong()).toString()),
            currency = currency,
            paidBy = composition(payers, total, random).mapValues { Money(it.value) },
            owedBy = composition(participants, total, random).mapValues { Money(it.value) },
        )
    }

    /** Random non-negative parts that sum exactly to [total], one per member. */
    fun composition(members: List<MemberId>, total: Long, random: Random): Map<MemberId, Long> {
        var remaining = total
        val parts = LinkedHashMap<MemberId, Long>()
        members.forEachIndexed { index, member ->
            val part = if (index == members.lastIndex) {
                remaining
            } else {
                if (remaining == 0L) 0L else random.nextLong(0, remaining + 1)
            }
            parts[member] = part
            remaining -= part
        }
        return parts
    }

    private fun List<MemberId>.withRandomWeights(seed: Long): Map<MemberId, Long> {
        val random = Random(seed * 31 + size)
        val weights = associateWith { random.nextLong(0, 1000L) }
        return if (weights.values.all { it == 0L }) weights.mapValues { 1L } else weights
    }
}
