// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LargestRemainderTest : FunSpec({

    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")
    val c = MemberId("0c000000-0000-4000-8000-000000000003")

    test("given unequal weights when remainders differ then leftover units go to the largest fractional remainder first") {
        // 101 by weights 1:2 -> exact 33.67 / 67.33; the single leftover unit goes to the larger remainder (a).
        val result = SplitCalculator.split(Money(101), SplitSpec.ByShares(mapOf(a to 1L, b to 2L)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe mapOf(a to 34L, b to 67L)
    }

    test("given equal fractional remainders when leftover distributed then ties break by ascending member UUID") {
        // 200 by weights 1:1:1 -> exact 66.67 each; two leftover units go to the two lowest UUIDs.
        val result = SplitCalculator.split(Money(200), SplitSpec.ByShares(mapOf(c to 1L, b to 1L, a to 1L)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 67L, b to 67L, c to 66L)
    }

    test("given a member with zero weight when others absorb the total then the zero-weight member never receives a leftover unit") {
        val result = SplitCalculator.split(Money(101), SplitSpec.ByShares(mapOf(a to 0L, b to 1L, c to 1L)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.first { it.memberId == a }.amount shouldBe Money.ZERO
        success.allocations.sumOf { it.amount.minorUnits } shouldBe 101
    }

    test("given allocations when result is returned then rows are sorted by member id and adjustments mark the absorbers") {
        val result = SplitCalculator.split(Money(1000), SplitSpec.Equal(setOf(b, c, a)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.memberId } shouldBe listOf(a, b, c)
        success.allocations.sumOf { it.roundingAdjustment } shouldBe 1
    }
})
