// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * One named test per edge case in SPEC §6.1 (cases 1-10; case 11 is the
 * sum-invariance property in [SplitPropertyTest]).
 */
class MoneyEdgeCasesTest : FunSpec({

    // Fixed UUIDs with a known sort order: a < b < c < d < e.
    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")
    val c = MemberId("0c000000-0000-4000-8000-000000000003")
    val d = MemberId("0d000000-0000-4000-8000-000000000004")
    val e = MemberId("0e000000-0000-4000-8000-000000000005")

    test("edge 1: given ten dollars when split equally among three then shares are 334, 333, 333 and the extra cent goes to the lowest member id") {
        val result = SplitCalculator.split(Money(1000), SplitSpec.Equal(setOf(c, a, b)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount.minorUnits } shouldBe listOf(334, 333, 333)
        success.allocations.map { it.memberId } shouldBe listOf(a, b, c)
        success.allocations.first { it.memberId == a }.roundingAdjustment shouldBe 1
        success.allocations.first { it.memberId == b }.roundingAdjustment shouldBe 0
    }

    test("edge 2: given one cent when split equally among five then one member gets the cent and four get zero, reported as zero shares") {
        val result = SplitCalculator.split(Money(1), SplitSpec.Equal(setOf(a, b, c, d, e)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.sumOf { it.amount.minorUnits } shouldBe 1
        success.allocations.first { it.memberId == a }.amount shouldBe Money(1)
        success.zeroShareMembers shouldBe listOf(b, c, d, e)
    }

    test("edge 3: given a zero-amount expense when split then it succeeds with all-zero shares flagged") {
        val result = SplitCalculator.split(Money.ZERO, SplitSpec.Equal(setOf(a, b)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount } shouldBe listOf(Money.ZERO, Money.ZERO)
        success.zeroShareMembers shouldBe listOf(a, b)
    }

    test("edge 4: given a negative total when split then it is rejected as a typed failure") {
        val result = SplitCalculator.split(Money(-500), SplitSpec.Equal(setOf(a, b)))
        result.shouldBeInstanceOf<SplitResult.Failure.NegativeTotal>()
    }

    test("edge 5: given Long MAX_VALUE minor units when split equally among seven then the sum is exact with no overflow") {
        val members = setOf(a, b, c, d, e, MemberId("0f000000-0000-4000-8000-000000000006"), MemberId("10000000-0000-4000-8000-000000000007"))
        val result = SplitCalculator.split(Money(Long.MAX_VALUE), SplitSpec.Equal(members))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.fold(0L) { acc, alloc -> Math.addExact(acc, alloc.amount.minorUnits) } shouldBe Long.MAX_VALUE
    }

    test("edge 5: given exact amounts whose sum overflows Long when split then it fails typed instead of wrapping") {
        val result = SplitCalculator.split(
            Money(Long.MAX_VALUE),
            SplitSpec.ByExactAmounts(mapOf(a to Money(Long.MAX_VALUE), b to Money(1))),
        )
        result.shouldBeInstanceOf<SplitResult.Failure.ArithmeticOverflow>()
    }

    test("edge 6: given 1000 yen when split equally among three then shares are whole yen 334, 333, 333") {
        val total = Money.fromMajor(1000, CurrencyCode("JPY"))
        val result = SplitCalculator.split(total, SplitSpec.Equal(setOf(a, b, c)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount.minorUnits } shouldBe listOf(334, 333, 333)
    }

    test("edge 7: given 10 Kuwaiti dinars when split equally among three then shares are in mils summing exactly") {
        val total = Money.fromMajor(10, CurrencyCode("KWD"))
        total shouldBe Money(10_000)
        val result = SplitCalculator.split(total, SplitSpec.Equal(setOf(a, b, c)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount.minorUnits } shouldBe listOf(3334, 3333, 3333)
    }

    test("edge 7: given 5 Bahraini dinars when split equally among two then each share is 2500 fils") {
        val total = Money.fromMajor(5, CurrencyCode("BHD"))
        val result = SplitCalculator.split(total, SplitSpec.Equal(setOf(a, b)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount.minorUnits } shouldBe listOf(2500, 2500)
    }

    test("edge 8: given percentages summing to 99.99 percent when split then save is blocked with the exact shortfall") {
        val result = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByPercentage(mapOf(a to 3333L, b to 3333L, c to 3333L)),
        )
        val failure = result.shouldBeInstanceOf<SplitResult.Failure.PercentagesDoNotSumTo100>()
        failure.sumBasisPoints shouldBe 9999L
        failure.shortfallBasisPoints shouldBe 1L
    }

    test("edge 9: given exact amounts summing to 9.99 of a 10.00 total when split then save is blocked with the remaining cent reported") {
        val result = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByExactAmounts(mapOf(a to Money(500), b to Money(499))),
        )
        val failure = result.shouldBeInstanceOf<SplitResult.Failure.AmountsDoNotSumToTotal>()
        failure.assigned shouldBe Money(999)
        failure.remaining shouldBe Money(1)
    }

    test("edge 10: given all shares set to zero when split then save is blocked with a typed failure") {
        val result = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByShares(mapOf(a to 0L, b to 0L, c to 0L)),
        )
        result.shouldBeInstanceOf<SplitResult.Failure.AllSharesZero>()
    }

    test("given no participants when split equally then it fails typed rather than dividing by zero") {
        SplitCalculator.split(Money(1000), SplitSpec.Equal(emptySet()))
            .shouldBeInstanceOf<SplitResult.Failure.NoParticipants>()
    }

    test("given a negative exact amount when split then it fails typed") {
        SplitCalculator.split(Money(100), SplitSpec.ByExactAmounts(mapOf(a to Money(200), b to Money(-100))))
            .shouldBeInstanceOf<SplitResult.Failure.NegativeShare>()
    }

    test("given a negative share weight when split by shares then it fails typed") {
        SplitCalculator.split(Money(100), SplitSpec.ByShares(mapOf(a to 2L, b to -1L)))
            .shouldBeInstanceOf<SplitResult.Failure.InvalidWeight>()
    }

    test("given 1000 won when split by shares two to one then shares are 667 and 333 whole won") {
        val total = Money.fromMajor(1000, CurrencyCode("KRW"))
        val result = SplitCalculator.split(total, SplitSpec.ByShares(mapOf(a to 2L, b to 1L)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.map { it.amount.minorUnits } shouldBe listOf(667, 333)
    }

    test("given an adjustment split when deltas leave a remainder then base is split equally and deltas applied on top") {
        // Total 10.00: B gets +2.00 for the cocktail; base 8.00 splits 2.67/2.67/2.66... -> largest remainder.
        val result = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByAdjustment(setOf(a, b, c), mapOf(b to Money(200))),
        )
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        // Base 8.00 over three: 267/267/266 (leftover cents to lowest UUIDs), then +2.00 on b.
        val amounts = success.allocations.associate { it.memberId to it.amount.minorUnits }
        amounts shouldBe mapOf(a to 267L, b to 467L, c to 266L)
    }

    test("given adjustments exceeding the total when split by adjustment then it fails typed") {
        SplitCalculator.split(Money(100), SplitSpec.ByAdjustment(setOf(a, b), mapOf(a to Money(200))))
            .shouldBeInstanceOf<SplitResult.Failure.AdjustmentsExceedTotal>()
    }

    test("given a negative delta larger than the equal share when split by adjustment then the negative result share fails typed") {
        // Base 10.00 over two is 5.00 each; a delta of -6.00 would drive one share below zero.
        SplitCalculator.split(Money(1000), SplitSpec.ByAdjustment(setOf(a, b), mapOf(a to Money(-600), b to Money(600))))
            .shouldBeInstanceOf<SplitResult.Failure.NegativeShare>()
    }

    test("given five members when percentage split is 25, 25, 25, 25, 0 then the zero-percent member is included with a zero share") {
        val result = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByPercentage(mapOf(a to 2500L, b to 2500L, c to 2500L, d to 2500L, e to 0L)),
        )
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations shouldHaveSize 5
        success.allocations.first { it.memberId == e }.amount shouldBe Money.ZERO
        success.zeroShareMembers shouldBe listOf(e)
    }
})
