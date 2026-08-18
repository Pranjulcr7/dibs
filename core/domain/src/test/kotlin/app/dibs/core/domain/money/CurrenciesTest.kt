// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CurrenciesTest : FunSpec({

    test("given common 2-decimal currencies when exponent looked up then it is 2") {
        listOf("USD", "EUR", "GBP", "INR", "BRL", "AUD").forEach { code ->
            Currencies.minorUnitExponent(CurrencyCode(code)) shouldBe 2
        }
    }

    test("given zero-decimal currency JPY when exponent looked up then it is 0") {
        Currencies.minorUnitExponent(CurrencyCode("JPY")) shouldBe 0
        Money.fromMajor(1000, CurrencyCode("JPY")) shouldBe Money(1000)
    }

    test("given zero-decimal currency KRW when exponent looked up then it is 0") {
        Currencies.minorUnitExponent(CurrencyCode("KRW")) shouldBe 0
        Money.fromMajor(50_000, CurrencyCode("KRW")) shouldBe Money(50_000)
    }

    test("given three-decimal currency KWD when exponent looked up then it is 3") {
        Currencies.minorUnitExponent(CurrencyCode("KWD")) shouldBe 3
        Money.fromMajor(10, CurrencyCode("KWD")) shouldBe Money(10_000)
    }

    test("given three-decimal currencies BHD and TND when exponent looked up then it is 3") {
        Currencies.minorUnitExponent(CurrencyCode("BHD")) shouldBe 3
        Currencies.minorUnitExponent(CurrencyCode("TND")) shouldBe 3
    }

    test("given an unknown currency code when exponent looked up then it defaults to 2") {
        Currencies.minorUnitExponent(CurrencyCode("XXX")) shouldBe 2
    }

    test("given a malformed currency code when constructed then it is rejected") {
        shouldThrow<IllegalArgumentException> { CurrencyCode("usd") }
        shouldThrow<IllegalArgumentException> { CurrencyCode("US") }
        shouldThrow<IllegalArgumentException> { CurrencyCode("") }
    }
})
