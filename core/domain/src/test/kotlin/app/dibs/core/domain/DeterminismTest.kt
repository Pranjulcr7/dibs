// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain

import app.dibs.core.domain.balance.Balances
import app.dibs.core.domain.balance.Expense
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.settle.Settlement
import app.dibs.core.domain.split.SplitCalculator
import app.dibs.core.domain.split.SplitResult
import app.dibs.core.domain.split.SplitSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random

/**
 * Two devices that compute the same split, balance, or settlement must render
 * byte-identical results (CLAUDE.md invariant 6). These properties re-run every
 * algorithm on the same logical input with collection orderings shuffled and
 * compare canonical byte encodings.
 */
class DeterminismTest : FunSpec({

    fun SplitResult.canonicalBytes(): ByteArray = when (this) {
        is SplitResult.Success ->
            allocations.joinToString("\n") { "${it.memberId.value}|${it.amount.minorUnits}|${it.roundingAdjustment}" }
        is SplitResult.Failure -> toString()
    }.toByteArray(Charsets.UTF_8)

    fun <K, V> Map<K, V>.shuffled(random: Random): Map<K, V> =
        entries.shuffled(random).associate { it.key to it.value }

    fun SplitSpec.shuffled(random: Random): SplitSpec = when (this) {
        is SplitSpec.Equal -> SplitSpec.Equal(participants.shuffled(random).toSet())
        is SplitSpec.ByExactAmounts -> SplitSpec.ByExactAmounts(amounts.shuffled(random))
        is SplitSpec.ByPercentage -> SplitSpec.ByPercentage(basisPoints.shuffled(random))
        is SplitSpec.ByShares -> SplitSpec.ByShares(shares.shuffled(random))
        is SplitSpec.ByAdjustment ->
            SplitSpec.ByAdjustment(participants.shuffled(random).toSet(), adjustments.shuffled(random))
        is SplitSpec.Itemized ->
            SplitSpec.Itemized(items.map { it.copy(consumers = it.consumers.shuffled(random)) }, tax, tip)
    }

    test("given 10000 random splits when input ordering is shuffled and rerun then output bytes are identical") {
        val arb = Arb.bind(DomainArbs.anyValidSpec, Arb.long()) { spec, seed -> spec to seed }
        checkAll(10_000, arb) { (input, seed) ->
            val (total, spec) = input
            val random = Random(seed)
            val first = SplitCalculator.split(total, spec).canonicalBytes()
            val rerun = SplitCalculator.split(total, spec).canonicalBytes()
            val shuffledRun = SplitCalculator.split(total, spec.shuffled(random)).canonicalBytes()
            rerun.contentEquals(first) shouldBe true
            shuffledRun.contentEquals(first) shouldBe true
        }
    }

    test("given 10000 random ledgers when expense ordering is shuffled and rerun then net balance bytes are identical") {
        fun canonical(net: Map<app.dibs.core.domain.money.CurrencyCode, List<MemberBalance>>): ByteArray =
            net.entries.joinToString("\n") { (currency, balances) ->
                currency.value + ":" + balances.joinToString(",") { "${it.memberId.value}=${it.net.minorUnits}" }
            }.toByteArray(Charsets.UTF_8)

        val arb = Arb.bind(DomainArbs.expenses, Arb.long()) { expenses, seed -> expenses to seed }
        checkAll(10_000, arb) { (expenses: List<Expense>, seed) ->
            val random = Random(seed)
            val first = canonical(Balances.net(expenses))
            val shuffledRun = canonical(
                Balances.net(
                    expenses.shuffled(random).map {
                        it.copy(paidBy = it.paidBy.shuffled(random), owedBy = it.owedBy.shuffled(random))
                    },
                ),
            )
            shuffledRun.contentEquals(first) shouldBe true
        }
    }

    test("given 10000 random balance sets when ordering is shuffled and rerun then the suggested transfer list is byte-identical") {
        fun canonical(transfers: List<app.dibs.core.domain.settle.Transfer>): ByteArray =
            transfers.joinToString("\n") { "${it.from.value}>${it.to.value}:${it.amount.minorUnits}" }
                .toByteArray(Charsets.UTF_8)

        val arb = Arb.bind(DomainArbs.zeroSumBalances, Arb.long()) { balances, seed -> balances to seed }
        checkAll(10_000, arb) { (balances, seed) ->
            val random = Random(seed)
            val first = canonical(Settlement.simplify(balances))
            val rerun = canonical(Settlement.simplify(balances))
            val shuffledRun = canonical(Settlement.simplify(balances.shuffled(random)))
            rerun.contentEquals(first) shouldBe true
            shuffledRun.contentEquals(first) shouldBe true
        }
    }
})
