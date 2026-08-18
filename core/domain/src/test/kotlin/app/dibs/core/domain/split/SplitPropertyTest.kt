// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.DomainArbs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.checkAll

/**
 * SPEC §6.1 edge case 11: repeated float accumulation is impossible by
 * construction; these properties prove sum invariance over at least 10,000
 * random splits per mode (CLAUDE.md invariant 2).
 */
class SplitPropertyTest : FunSpec({

    suspend fun assertSumInvariance(specs: io.kotest.property.Arb<Pair<app.dibs.core.domain.money.Money, SplitSpec>>) {
        checkAll(10_000, specs) { (total, spec) ->
            val result = SplitCalculator.split(total, spec)
            val success = result.shouldBeInstanceOf<SplitResult.Success>()
            success.allocations.fold(0L) { acc, alloc -> Math.addExact(acc, alloc.amount.minorUnits) } shouldBe
                total.minorUnits
            success.allocations.forEach { (it.amount.minorUnits >= 0) shouldBe true }
        }
    }

    test("edge 11: given 10000 random equal splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.equalSpec)
    }

    test("given 10000 random share splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.sharesSpec)
    }

    test("given 10000 random percentage splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.percentageSpec)
    }

    test("given 10000 random exact-amount splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.exactSpec)
    }

    test("given 10000 random adjustment splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.adjustmentSpec)
    }

    test("given 10000 random itemized splits when allocated then every split sums exactly to its total") {
        assertSumInvariance(DomainArbs.itemizedSpec)
    }

    test("given 10000 random splits when allocated then each share differs from the exact proportional share by less than one minor unit") {
        checkAll(10_000, DomainArbs.sharesSpec) { (total, spec) ->
            val shares = (spec as SplitSpec.ByShares).shares
            val weightSum = shares.values.sum()
            val success = SplitCalculator.split(total, spec).shouldBeInstanceOf<SplitResult.Success>()
            success.allocations.forEach { alloc ->
                val exactTimesWeightSum = total.minorUnits.toBigInteger() * shares.getValue(alloc.memberId).toBigInteger()
                val allocatedTimesWeightSum = alloc.amount.minorUnits.toBigInteger() * weightSum.toBigInteger()
                val diff = (allocatedTimesWeightSum - exactTimesWeightSum).abs()
                // |allocated - exact| < 1 minor unit  <=>  |allocated*W - total*w| < W
                (diff < weightSum.toBigInteger()) shouldBe true
            }
        }
    }
})
