// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.importer

import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Currencies
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.split.LargestRemainder
import org.apache.commons.codec.digest.Blake3
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Parser for Splitwise's per-group CSV export (FR-I1):
 * `Date,Description,Category,Cost,Currency,<Member1>,<Member2>,...` — one row
 * per expense, each member column holding that member's **signed net** (paid
 * minus owed).
 *
 * Reconstruction: negative columns are debtors' shares verbatim; the residual
 * owed (cost − Σ debtor shares) is split among the positive columns by
 * largest remainder weighted by credit, and each payer's paid amount is their
 * share plus credit — so paid and owed both sum exactly to the cost and every
 * member's net matches the export to the cent. Rows that cannot satisfy that
 * are *skipped with a reason*, never guessed at (SPEC §4.2: specific errors).
 *
 * Money is parsed straight to minor units; no floats anywhere (invariant 1).
 */
object SplitwiseCsv {

    private val EXPECTED_HEADER = listOf("Date", "Description", "Category", "Cost", "Currency")
    private const val MEMBER_COLUMN_OFFSET = 5

    sealed interface Row {
        data class Expense(
            /** Stable per (file, row content) — re-imports dedupe (edge 55). */
            val stableId: String,
            val dateEpochDay: Long,
            val description: String,
            val category: String?,
            val currency: String,
            val totalMinor: Long,
            val paidByMinor: Map<String, Long>,
            val owedByMinor: Map<String, Long>,
        ) : Row

        data class Settlement(
            val stableId: String,
            val dateEpochDay: Long,
            val fromName: String,
            val toName: String,
            val currency: String,
            val amountMinor: Long,
        ) : Row
    }

    data class SkippedRow(val lineNumber: Int, val content: String, val reason: String)

    sealed interface Result {
        data class Success(
            val memberNames: List<String>,
            val rows: List<Row>,
            val skipped: List<SkippedRow>,
            val currencyTotalsMinor: Map<String, Long>,
            /** False when a "Total balance" trailer disagrees with computed nets (edge 52). */
            val balanceChecksumMatches: Boolean,
        ) : Result

        /** Header not the English export shape — tell the user to re-export (edge 48). */
        data object UnrecognizedHeader : Result
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // a parser is a chain of reject-early gates
    fun parse(text: String): Result {
        val lines = text.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result.UnrecognizedHeader
        val headerFields = splitCsvLine(lines.first())
        if (headerFields.size <= MEMBER_COLUMN_OFFSET ||
            headerFields.take(MEMBER_COLUMN_OFFSET) != EXPECTED_HEADER
        ) {
            return Result.UnrecognizedHeader
        }
        val memberNames = headerFields.drop(MEMBER_COLUMN_OFFSET)
        val fileTag = hash(text.toByteArray(Charsets.UTF_8))

        val rows = mutableListOf<Row>()
        val skipped = mutableListOf<SkippedRow>()
        val currencyTotals = LinkedHashMap<String, Long>()
        val computedNets = HashMap<String, Long>()
        var checksumMatches = true

        lines.drop(1).forEachIndexed { index, line ->
            val lineNumber = index + 2
            val fields = splitCsvLine(line)
            if (fields.size != headerFields.size) {
                skipped += SkippedRow(lineNumber, line, "wrong column count")
                return@forEachIndexed
            }
            val description = fields[1]
            val currencyText = fields[4].trim()
            val nets = memberNames.indices.map { i -> parseAmountMinor(fields[MEMBER_COLUMN_OFFSET + i], currencyText) }

            if (description.equals("Total balance", ignoreCase = true)) {
                // Trailer: use as a checksum, never import (edge 52).
                if (nets.any { it == null }) return@forEachIndexed
                memberNames.forEachIndexed { i, name ->
                    if ((computedNets[name] ?: 0L) != nets[i]) checksumMatches = false
                }
                return@forEachIndexed
            }

            val date = try {
                LocalDate.parse(fields[0].trim()).toEpochDay()
            } catch (_: DateTimeParseException) {
                skipped += SkippedRow(lineNumber, line, "unreadable date '${fields[0]}'")
                return@forEachIndexed
            }
            val cost = parseAmountMinor(fields[3], currencyText)
            if (cost == null || cost < 0) {
                skipped += SkippedRow(lineNumber, line, "unreadable cost '${fields[3]}'")
                return@forEachIndexed
            }
            if (nets.any { it == null }) {
                skipped += SkippedRow(lineNumber, line, "unreadable share amount")
                return@forEachIndexed
            }
            val netByName = memberNames.zip(nets.map { checkNotNull(it) }).toMap()
            if (netByName.values.sum() != 0L) {
                skipped += SkippedRow(lineNumber, line, "shares do not balance to zero")
                return@forEachIndexed
            }

            val stableId = hash((fileTag + "|" + line).toByteArray(Charsets.UTF_8))
            val isPayment = fields[2].trim().equals("Payment", ignoreCase = true)
            if (isPayment) {
                val from = netByName.entries.filter { it.value > 0 }
                val to = netByName.entries.filter { it.value < 0 }
                if (from.size != 1 || to.size != 1 || from.single().value != -to.single().value) {
                    skipped += SkippedRow(lineNumber, line, "payment row is not a single transfer")
                    return@forEachIndexed
                }
                rows += Row.Settlement(
                    stableId = stableId,
                    dateEpochDay = date,
                    fromName = from.single().key,
                    toName = to.single().key,
                    currency = currencyText,
                    amountMinor = from.single().value,
                )
                netByName.forEach { (name, net) -> computedNets.merge(name, net, Long::plus) }
                return@forEachIndexed
            }

            val debtors = netByName.filterValues { it < 0 }.mapValues { -it.value }
            val creditors = netByName.filterValues { it > 0 }
            val residualOwed = cost - debtors.values.sum()
            if (residualOwed < 0 || creditors.isEmpty()) {
                skipped += SkippedRow(lineNumber, line, "shares exceed the cost")
                return@forEachIndexed
            }
            // Split the payers' own consumption by credit weight, exactly.
            val payerOwed = LargestRemainder.allocate(
                residualOwed,
                creditors.entries
                    .sortedBy { it.key }
                    .withIndex()
                    .associate { (i, entry) -> MemberId("c$i:${entry.key}") to BigInteger.valueOf(entry.value) },
            ).associate { allocation ->
                allocation.memberId.value.substringAfter(':') to allocation.amount.minorUnits
            }
            val owedBy = debtors + payerOwed.filterValues { it != 0L }
            val paidBy = creditors.mapValues { (name, credit) -> credit + (payerOwed[name] ?: 0L) }

            rows += Row.Expense(
                stableId = stableId,
                dateEpochDay = date,
                description = description,
                category = fields[2].trim().ifEmpty { null },
                currency = currencyText,
                totalMinor = cost,
                paidByMinor = paidBy,
                owedByMinor = owedBy,
            )
            currencyTotals.merge(currencyText, cost, Long::plus)
            netByName.forEach { (name, net) -> computedNets.merge(name, net, Long::plus) }
        }

        return Result.Success(memberNames, rows, skipped, currencyTotals, checksumMatches)
    }

    /** Signed amount text to minor units; `.` or `,` decimals detected per value (edge 49). */
    private fun parseAmountMinor(raw: String, currencyText: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        val negative = text.startsWith("-")
        val body = text.removePrefix("-")
        val exponent = runCatching { Currencies.minorUnitExponent(CurrencyCode(currencyText)) }.getOrNull() ?: 2
        val pieces = body.split('.', ',')
        if (pieces.size > 2 || pieces.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
        val whole = pieces[0].toLongOrNull() ?: return null
        val fraction = pieces.getOrElse(1) { "" }
        if (fraction.length > exponent) return null
        val minor = whole * pow10(exponent) + (fraction.padEnd(exponent, '0').ifEmpty { "0" }.toLong())
        return if (negative) -minor else minor
    }

    private fun pow10(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result *= 10 }
        return result
    }

    /** Minimal RFC-4180 field splitting: quotes, embedded commas, doubled quotes. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    private fun hash(bytes: ByteArray): String {
        val out = ByteArray(HASH_BYTES)
        Blake3.initHash().update(bytes).doFinalize(out)
        return HexBytes.toHex(out)
    }

    private const val HASH_BYTES = 16
}
