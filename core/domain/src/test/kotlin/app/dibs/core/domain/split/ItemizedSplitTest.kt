// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ItemizedSplitTest : FunSpec({

    val a = MemberId("0a000000-0000-4000-8000-000000000001")
    val b = MemberId("0b000000-0000-4000-8000-000000000002")

    test("given items with tax and tip when split then tax and tip are prorated by each member's item subtotal") {
        // a: burger 10.00 + half the fries (3.00) = 13.00; b: salad 8.00 + half the fries = 11.00.
        // Subtotal 24.00, tax 2.40, tip 3.60, total 30.00. Shares scale by 30/24: a 16.25, b 13.75.
        val items = listOf(
            LineItem(price = Money(1000), consumers = mapOf(a to 1L)),
            LineItem(price = Money(800), consumers = mapOf(b to 1L)),
            LineItem(price = Money(600), consumers = mapOf(a to 1L, b to 1L)),
        )
        val result = SplitCalculator.split(
            Money(3000),
            SplitSpec.Itemized(items, tax = Money(240), tip = Money(360)),
        )
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 1625L, b to 1375L)
    }

    test("given proration that does not land on whole cents when split then rounding happens once against the true total") {
        // a items 1.00, b items 2.00, tax 1.00, total 4.00 -> exact shares 1.3333 / 2.6667 -> 133 / 267.
        val items = listOf(
            LineItem(price = Money(100), consumers = mapOf(a to 1L)),
            LineItem(price = Money(200), consumers = mapOf(b to 1L)),
        )
        val result = SplitCalculator.split(Money(400), SplitSpec.Itemized(items, tax = Money(100), tip = Money.ZERO))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 133L, b to 267L)
    }

    test("given an item shared with unequal weights when split then consumption follows the weights") {
        // 9.00 of nachos, a ate two thirds: a 6.00, b 3.00.
        val items = listOf(LineItem(price = Money(900), consumers = mapOf(a to 2L, b to 1L)))
        val result = SplitCalculator.split(Money(900), SplitSpec.Itemized(items, Money.ZERO, Money.ZERO))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 600L, b to 300L)
    }

    test("given a shared discount line item when split then it reduces the subtotal used for tax proration") {
        // Meals 10.00 each, shared coupon -4.00: subtotals 8.00/8.00; 10% tax 1.60; total 17.60.
        val items = listOf(
            LineItem(price = Money(1000), consumers = mapOf(a to 1L)),
            LineItem(price = Money(1000), consumers = mapOf(b to 1L)),
            LineItem(price = Money(-400), consumers = mapOf(a to 1L, b to 1L)),
        )
        val result = SplitCalculator.split(Money(1760), SplitSpec.Itemized(items, tax = Money(160), tip = Money.ZERO))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 880L, b to 880L)
    }

    test("given a discount that drives one member's subtotal negative when split then it fails typed") {
        val items = listOf(
            LineItem(price = Money(1000), consumers = mapOf(a to 1L)),
            LineItem(price = Money(-400), consumers = mapOf(b to 1L)),
        )
        SplitCalculator.split(Money(600), SplitSpec.Itemized(items, Money.ZERO, Money.ZERO))
            .shouldBeInstanceOf<SplitResult.Failure.NegativeShare>()
    }

    test("given items that do not sum to the expense total when split then it fails with both numbers reported") {
        val items = listOf(LineItem(price = Money(1000), consumers = mapOf(a to 1L)))
        val result = SplitCalculator.split(Money(1200), SplitSpec.Itemized(items, Money.ZERO, Money.ZERO))
        val failure = result.shouldBeInstanceOf<SplitResult.Failure.ItemsDoNotSumToTotal>()
        failure.computed shouldBe Money(1000)
        failure.total shouldBe Money(1200)
    }

    test("given an item with no consumers when split then it fails typed") {
        val items = listOf(LineItem(price = Money(1000), consumers = emptyMap()))
        SplitCalculator.split(Money(1000), SplitSpec.Itemized(items, Money.ZERO, Money.ZERO))
            .shouldBeInstanceOf<SplitResult.Failure.ItemHasNoConsumers>()
    }

    test("given a zero item subtotal with nonzero tip when split then tip is divided equally among participants") {
        // All items are zero-priced placeholders; the 1.00 tip has no subtotals to prorate against.
        val items = listOf(LineItem(price = Money.ZERO, consumers = mapOf(a to 1L, b to 1L)))
        val result = SplitCalculator.split(Money(100), SplitSpec.Itemized(items, Money.ZERO, tip = Money(100)))
        val success = result.shouldBeInstanceOf<SplitResult.Success>()
        success.allocations.associate { it.memberId to it.amount.minorUnits } shouldBe
            mapOf(a to 50L, b to 50L)
    }

    test("given negative tax or tip when split then it fails typed") {
        val items = listOf(LineItem(price = Money(1000), consumers = mapOf(a to 1L)))
        SplitCalculator.split(Money(900), SplitSpec.Itemized(items, tax = Money(-100), tip = Money.ZERO))
            .shouldBeInstanceOf<SplitResult.Failure.NegativeTaxOrTip>()
    }
})
