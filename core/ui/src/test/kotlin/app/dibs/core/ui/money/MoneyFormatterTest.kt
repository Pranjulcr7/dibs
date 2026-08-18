// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.money

import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoneyFormatterTest {

    @Test
    fun `given a 2-decimal currency when formatted then symbol and two decimals appear`() {
        assertEquals("$10.00", MoneyFormatter.format(Money(1000), CurrencyCode("USD"), Locale.US))
    }

    @Test
    fun `given a zero-decimal currency when formatted then no decimal separator appears`() {
        val formatted = MoneyFormatter.format(Money(1000), CurrencyCode("JPY"), Locale.US)
        assertEquals("¥1,000", formatted)
        assertFalse(formatted.contains("."))
    }

    @Test
    fun `given a three-decimal currency when formatted then three decimals appear from the domain exponent`() {
        val formatted = MoneyFormatter.format(Money(10_000), CurrencyCode("KWD"), Locale.US)
        assertTrue("expected three decimals in '$formatted'", formatted.contains("10.000"))
    }

    @Test
    fun `given a negative amount when formatted then the sign is preserved`() {
        val formatted = MoneyFormatter.format(Money(-1250), CurrencyCode("USD"), Locale.US)
        assertTrue("expected a negative rendering, got '$formatted'", formatted.contains("12.50"))
        assertTrue(formatted.startsWith("-") || formatted.startsWith("($"))
    }

    @Test
    fun `given one minor unit when formatted then no rounding is introduced by display`() {
        assertEquals("$0.01", MoneyFormatter.format(Money(1), CurrencyCode("USD"), Locale.US))
    }

    @Test
    fun `given a multi-currency context when formatted with the code then the code is explicit`() {
        val formatted = MoneyFormatter.formatWithCode(Money(1000), CurrencyCode("EUR"), Locale.US)
        assertTrue("expected the ISO code in '$formatted'", formatted.contains("EUR"))
    }
}
