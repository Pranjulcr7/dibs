// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MoneyTest : FunSpec({

    test("given two amounts when added then result is exact integer arithmetic") {
        (Money(1050) + Money(999)) shouldBe Money(2049)
    }

    test("given two amounts when subtracted then result can be negative") {
        (Money(100) - Money(250)) shouldBe Money(-150)
        Money(-150).isNegative shouldBe true
    }

    test("given amounts near Long MAX_VALUE when added then overflow throws instead of wrapping") {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        shouldThrow<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
    }

    test("given amounts when compared then ordering follows minor units") {
        (Money(200) > Money(199)) shouldBe true
        Money.ZERO.isZero shouldBe true
        Money(1).isPositive shouldBe true
    }

    test("given a major amount in a 2-decimal currency when converted then minor units multiply by 100") {
        Money.fromMajor(10, CurrencyCode("USD")) shouldBe Money(1000)
    }

    test("given a major amount when conversion overflows then it throws instead of wrapping") {
        shouldThrow<ArithmeticException> { Money.fromMajor(Long.MAX_VALUE / 10, CurrencyCode("USD")) }
    }
})
