// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.importer

import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.money.Currencies
import app.dibs.core.domain.money.CurrencyCode
import java.time.LocalDate

/**
 * CSV export in the same shape Splitwise produces (FR-I2), so a Dibs export is
 * itself re-importable — which the round-trip test exploits. Signed member
 * columns are `paid − owed` per expense; settlements export as Payment rows.
 */
object CsvExport {

    fun export(state: GroupState): String {
        val members = state.members.values.filterNot { it.removed }.sortedBy { it.id }
        val sb = StringBuilder()
        sb.append("Date,Description,Category,Cost,Currency")
        members.forEach { sb.append(',').append(quote(it.name)) }
        sb.append('\n')

        state.activeExpenses
            .sortedWith(compareBy({ it.fields.dateEpochDay }, { it.id }))
            .forEach { expense ->
                sb.append(LocalDate.ofEpochDay(expense.fields.dateEpochDay))
                sb.append(',').append(quote(expense.fields.description))
                sb.append(',').append(quote(expense.fields.category.orEmpty()))
                sb.append(',').append(formatMinor(expense.fields.total.minorUnits, expense.fields.currency))
                sb.append(',').append(expense.fields.currency.value)
                members.forEach { member ->
                    val paid = expense.fields.paidBy[member.id]?.minorUnits ?: 0L
                    val owed = expense.fields.owedBy[member.id]?.minorUnits ?: 0L
                    sb.append(',').append(formatMinor(paid - owed, expense.fields.currency))
                }
                sb.append('\n')
            }
        state.settlements.values
            .sortedBy { it.id }
            .forEach { settlement ->
                sb.append(LocalDate.EPOCH)
                sb.append(',').append(quote("Payment"))
                sb.append(",Payment")
                sb.append(',').append(formatMinor(settlement.amount.minorUnits, settlement.currency))
                sb.append(',').append(settlement.currency.value)
                members.forEach { member ->
                    val net = when (member.id) {
                        settlement.from -> settlement.amount.minorUnits
                        settlement.to -> -settlement.amount.minorUnits
                        else -> 0L
                    }
                    sb.append(',').append(formatMinor(net, settlement.currency))
                }
                sb.append('\n')
            }
        return sb.toString()
    }

    private fun formatMinor(minor: Long, currency: CurrencyCode): String {
        val exponent = Currencies.minorUnitExponent(currency)
        if (exponent == 0) return minor.toString()
        val negative = minor < 0
        val abs = if (negative) -minor else minor
        val per = Currencies.minorUnitsPerMajor(currency)
        return (if (negative) "-" else "") + "${abs / per}." + (abs % per).toString().padStart(exponent, '0')
    }

    private fun quote(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}
