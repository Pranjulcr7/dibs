// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.money

import android.icu.text.NumberFormat
import app.dibs.core.domain.money.Currencies
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/**
 * The only place money becomes display text (SPEC §5.1: display formatting is
 * the only locale-aware spot). Uses `android.icu` per CLAUDE.md — never a
 * hand-rolled pattern — but the minor-units→decimal shift and the fraction
 * digit count come from the domain's own exponent table, so what the user sees
 * always agrees with what the ledger computes, even if platform ICU data
 * disagrees about a currency's exponent.
 *
 * `BigDecimal` appears here for *display conversion only*; storage and
 * arithmetic remain `Long` minor units (invariant 1 bans it from storage).
 */
object MoneyFormatter {

    fun format(money: Money, currency: CurrencyCode, locale: Locale = Locale.getDefault()): String {
        val exponent = Currencies.minorUnitExponent(currency)
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = android.icu.util.Currency.getInstance(currency.value)
        format.minimumFractionDigits = exponent
        format.maximumFractionDigits = exponent
        return format.format(BigDecimal(BigInteger.valueOf(money.minorUnits), exponent))
    }

    /**
     * Formatting with the ISO code appended when the symbol alone is ambiguous —
     * multi-currency groups must always show the code (SPEC §4.2).
     */
    fun formatWithCode(money: Money, currency: CurrencyCode, locale: Locale = Locale.getDefault()): String {
        val formatted = format(money, currency, locale)
        return if (formatted.contains(currency.value)) formatted else "$formatted ${currency.value}"
    }
}
