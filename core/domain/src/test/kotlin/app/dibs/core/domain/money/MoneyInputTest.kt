// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MoneyInputTest : FunSpec({

    val usd = CurrencyCode("USD")
    val jpy = CurrencyCode("JPY")
    val kwd = CurrencyCode("KWD")

    test("given plain major units then parsing scales by the currency exponent") {
        MoneyInput.parse("10", usd) shouldBe Money(1000)
        MoneyInput.parse("10", jpy) shouldBe Money(10)
        MoneyInput.parse("10", kwd) shouldBe Money(10_000)
    }

    test("given decimals then both dot and comma separators are accepted") {
        MoneyInput.parse("10.50", usd) shouldBe Money(1050)
        MoneyInput.parse("10,50", usd) shouldBe Money(1050)
        MoneyInput.parse("0.05", usd) shouldBe Money(5)
        MoneyInput.parse(".5", usd) shouldBe Money(50)
    }

    test("given fewer fraction digits than the exponent then they are right-padded, never guessed") {
        MoneyInput.parse("10.5", usd) shouldBe Money(1050)
        MoneyInput.parse("10.5", kwd) shouldBe Money(10_500)
        MoneyInput.parse("10.12", kwd) shouldBe Money(10_120)
    }

    test("given more fraction digits than the currency allows then parsing fails rather than rounding") {
        MoneyInput.parse("10.123", usd) shouldBe null
        MoneyInput.parse("10.5", jpy) shouldBe null
        MoneyInput.parse("10.1234", kwd) shouldBe null
    }

    test("given garbage, negatives, or emptiness then parsing fails closed") {
        MoneyInput.parse("", usd) shouldBe null
        MoneyInput.parse("   ", usd) shouldBe null
        MoneyInput.parse("-5", usd) shouldBe null
        MoneyInput.parse("10.5.0", usd) shouldBe null
        MoneyInput.parse("1e3", usd) shouldBe null
        MoneyInput.parse("abc", usd) shouldBe null
        MoneyInput.parse("10,000.50", usd) shouldBe null
    }

    test("given a huge amount then overflow fails closed instead of wrapping") {
        MoneyInput.parse("92233720368547758.08", usd) shouldBe null
        MoneyInput.parse("92233720368547758070", jpy) shouldBe null
    }

    test("given signed text then parseSigned handles deltas and parse stays non-negative") {
        MoneyInput.parseSigned("-2.50", usd) shouldBe Money(-250)
        MoneyInput.parseSigned("2.50", usd) shouldBe Money(250)
        MoneyInput.parseSigned("--2", usd) shouldBe null
    }

    test("given minor units then toPlainString is the parse inverse") {
        MoneyInput.toPlainString(Money(1050), usd) shouldBe "10.50"
        MoneyInput.toPlainString(Money(5), usd) shouldBe "0.05"
        MoneyInput.toPlainString(Money(1000), jpy) shouldBe "1000"
        MoneyInput.toPlainString(Money(10_120), kwd) shouldBe "10.120"
        MoneyInput.toPlainString(Money(-250), usd) shouldBe "-2.50"
        MoneyInput.parse(MoneyInput.toPlainString(Money(123_456), usd), usd) shouldBe Money(123_456)
    }

    test("given percent text then basis points parse with at most two decimals") {
        MoneyInput.parseBasisPoints("33.33") shouldBe 3333L
        MoneyInput.parseBasisPoints("50") shouldBe 5000L
        MoneyInput.parseBasisPoints("0.5") shouldBe 50L
        MoneyInput.parseBasisPoints("33.333") shouldBe null
        MoneyInput.parseBasisPoints("101") shouldBe 10100L
        MoneyInput.parseBasisPoints("-1") shouldBe null
        MoneyInput.parseBasisPoints("") shouldBe null
    }
})
