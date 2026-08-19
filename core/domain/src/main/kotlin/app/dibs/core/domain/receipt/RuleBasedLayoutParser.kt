// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import kotlin.math.abs
import kotlin.math.min

/**
 * Turns OCR text boxes into a [ReceiptDraft] using layout rules, no ML:
 *
 * 1. Boxes group into visual rows by vertical overlap.
 * 2. A row is *monetary* when its rightmost box parses as a price **and**
 *    that price sits in the receipt's right-aligned price column (the modal
 *    right edge of all price-shaped boxes) — this is what keeps a stray "2"
 *    in "2 x Coffee" from being read as $2.
 * 3. Monetary rows classify by label keyword into subtotal / tax / tip /
 *    total / ignored (cash, change, card); everything else above the summary
 *    block is a line item, with "2 x" style quantity prefixes recognized.
 * 4. The arithmetic gate: items ± tax ± tip must reach the printed total
 *    within [TOLERANCE_MINOR] (2 minor units — "the 0.02 gate"). A failed
 *    gate never blocks review; it flags it (edges 39/40).
 *
 * Prices must have exactly two decimals ("12.34"); receipts print cents, and
 * requiring them is the cheapest defense against quantity/date noise.
 */
object RuleBasedLayoutParser {

    fun parse(boxes: List<TextBox>): ReceiptDraft {
        if (boxes.size < MIN_BOXES) {
            return ReceiptDraft(flags = setOf(ReceiptFlag.LOW_TEXT))
        }

        val rows = groupIntoRows(boxes)
        val priced = rows.mapNotNull { row -> pricedRowOf(row) }
        if (priced.isEmpty()) {
            return ReceiptDraft(flags = setOf(ReceiptFlag.LOW_TEXT))
        }

        val column = priceColumnRight(priced)
        val pageWidth = boxes.maxOf { it.right } - boxes.minOf { it.left }
        val tolerance = (pageWidth * COLUMN_TOLERANCE_FRACTION).toInt().coerceAtLeast(1)
        val monetary = priced.filter { abs(it.priceBox.right - column) <= tolerance }

        var subtotal: Long? = null
        var tax: Long? = null
        var tip: Long? = null
        var total: Long? = null
        val items = mutableListOf<ReceiptItem>()
        var unparsed = false
        var summarySeen = false

        monetary.forEach { row ->
            val label = row.label.trim()
            val kind = classify(label)
            when (kind) {
                RowKind.SUBTOTAL -> {
                    subtotal = row.amountMinor
                    summarySeen = true
                }
                RowKind.TAX -> {
                    tax = (tax ?: 0L) + row.amountMinor
                    summarySeen = true
                }
                RowKind.TIP -> {
                    tip = row.amountMinor
                    summarySeen = true
                }
                RowKind.TOTAL -> {
                    // The last total wins: receipts often print running totals.
                    total = row.amountMinor
                    summarySeen = true
                }
                RowKind.IGNORED -> Unit
                RowKind.ITEM ->
                    if (summarySeen) {
                        // A price row after the summary block that we can't
                        // name — flag it instead of guessing (edge 36).
                        unparsed = true
                    } else {
                        items += itemOf(label, row.amountMinor)
                    }
            }
        }

        val flags = mutableSetOf<ReceiptFlag>()
        if (unparsed) flags += ReceiptFlag.UNPARSED_REGIONS
        if (items.isEmpty() && total != null) flags += ReceiptFlag.TOTAL_ONLY
        if (items.isEmpty() && total == null) flags += ReceiptFlag.LOW_TEXT

        val gate = gate(items, subtotal, tax, tip, total)
        if (gate == false) flags += ReceiptFlag.ARITHMETIC_MISMATCH

        return ReceiptDraft(
            items = items,
            subtotalMinor = subtotal,
            taxMinor = tax,
            tipMinor = tip,
            totalMinor = total,
            gatePassed = gate,
            flags = flags,
        )
    }

    /**
     * Merges per-frame drafts from a long receipt (FR-R7, edge 35). Items
     * concatenate; at each frame boundary, the longest run that ends frame N
     * and re-starts frame N+1 (same labels and amounts, in order) is dropped
     * from N+1 — overlap capture is encouraged, duplicates are not. Summary
     * fields come from the last frame that has them (totals print at the end).
     */
    fun merge(frames: List<ReceiptDraft>): ReceiptDraft {
        if (frames.size == 1) return frames.single()
        if (frames.isEmpty()) return ReceiptDraft(flags = setOf(ReceiptFlag.LOW_TEXT))

        val items = mutableListOf<ReceiptItem>()
        frames.forEach { frame ->
            var incoming = frame.items
            val maxOverlap = min(items.size, incoming.size)
            for (len in maxOverlap downTo 1) {
                if (items.takeLast(len).map { it.key() } == incoming.take(len).map { it.key() }) {
                    incoming = incoming.drop(len)
                    break
                }
            }
            items += incoming
        }

        val subtotal = frames.lastOrNull { it.subtotalMinor != null }?.subtotalMinor
        val tax = frames.lastOrNull { it.taxMinor != null }?.taxMinor
        val tip = frames.lastOrNull { it.tipMinor != null }?.tipMinor
        val total = frames.lastOrNull { it.totalMinor != null }?.totalMinor
        val gate = gate(items, subtotal, tax, tip, total)

        val flags = frames.flatMap { it.flags }.toMutableSet()
        flags.remove(ReceiptFlag.ARITHMETIC_MISMATCH)
        if (items.isNotEmpty()) {
            flags.remove(ReceiptFlag.TOTAL_ONLY)
            flags.remove(ReceiptFlag.LOW_TEXT)
        }
        if (gate == false) flags += ReceiptFlag.ARITHMETIC_MISMATCH

        return ReceiptDraft(items, subtotal, tax, tip, total, gate, flags)
    }

    private fun ReceiptItem.key() = label.lowercase() to totalMinor

    private fun gate(items: List<ReceiptItem>, subtotal: Long?, tax: Long?, tip: Long?, total: Long?): Boolean? {
        if (total == null || items.isEmpty()) return null
        val itemsSum = items.sumOf { it.totalMinor }
        val expected = itemsSum + (tax ?: 0L) + (tip ?: 0L)
        val totalOk = abs(expected - total) <= TOLERANCE_MINOR
        val subtotalOk = subtotal == null || abs(itemsSum - subtotal) <= TOLERANCE_MINOR
        return totalOk && subtotalOk
    }

    // -- row assembly ------------------------------------------------------

    private fun groupIntoRows(boxes: List<TextBox>): List<List<TextBox>> {
        val sorted = boxes.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<TextBox>>()
        sorted.forEach { box ->
            val row = rows.lastOrNull()
            if (row != null && overlaps(row, box)) {
                row += box
            } else {
                rows += mutableListOf(box)
            }
        }
        return rows.map { it.sortedBy { box -> box.left } }
    }

    private fun overlaps(row: List<TextBox>, box: TextBox): Boolean {
        val rowTop = row.minOf { it.top }
        val rowBottom = row.maxOf { it.bottom }
        val overlap = min(rowBottom, box.bottom) - kotlin.math.max(rowTop, box.top)
        val smaller = min(rowBottom - rowTop, box.height).coerceAtLeast(1)
        return overlap >= smaller * ROW_OVERLAP_FRACTION
    }

    private data class PricedRow(val label: String, val amountMinor: Long, val priceBox: TextBox)

    private fun pricedRowOf(row: List<TextBox>): PricedRow? {
        val priceBox = row.lastOrNull { parsePrice(it.text) != null } ?: return null
        val amount = parsePrice(priceBox.text) ?: return null
        val label = row.filterNot { it === priceBox }.joinToString(" ") { it.text }
        return PricedRow(label, amount, priceBox)
    }

    private fun priceColumnRight(priced: List<PricedRow>): Int {
        // Modal right edge: the column with the most price boxes within a
        // small band. O(n²) over a receipt's row count is nothing.
        return priced
            .map { it.priceBox.right }
            .maxByOrNull { edge -> priced.count { abs(it.priceBox.right - edge) <= COLUMN_SEED_BAND } }
            ?: 0
    }

    // -- text parsing ------------------------------------------------------

    private val PRICE = Regex("""^\(?-?[$€£]?\s?-?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})\)?-?$""")

    /** Minor units, negative for "(1.50)", "-1.50" or "1.50-" (edge 41). */
    internal fun parsePrice(raw: String): Long? {
        val text = raw.trim()
        val match = PRICE.matchEntire(text) ?: return null
        val negative = text.startsWith("(") || text.contains('-')
        val units = match.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val cents = match.groupValues[2].toLong()
        val minor = units * MINOR_PER_UNIT + cents
        return if (negative) -minor else minor
    }

    private val QUANTITY_PREFIX = Regex("""^(\d{1,3})\s*[xX@]\s+""")
    private val QUANTITY_WORD = Regex("""^QTY\s*[:.]?\s*(\d{1,3})\s+""", RegexOption.IGNORE_CASE)

    private fun itemOf(label: String, amountMinor: Long): ReceiptItem {
        for (regex in listOf(QUANTITY_PREFIX, QUANTITY_WORD)) {
            val match = regex.find(label)
            if (match != null) {
                val quantity = match.groupValues[1].toIntOrNull() ?: 1
                return ReceiptItem(label.removeRange(match.range).trim(), quantity.coerceAtLeast(1), amountMinor)
            }
        }
        return ReceiptItem(label, 1, amountMinor)
    }

    private enum class RowKind { ITEM, SUBTOTAL, TAX, TIP, TOTAL, IGNORED }

    private fun classify(label: String): RowKind {
        val upper = label.uppercase()
        return when {
            upper.contains("SUBTOTAL") || upper.contains("SUB-TOTAL") || upper.contains("SUB TOTAL") ->
                RowKind.SUBTOTAL
            upper.contains("TAX") || upper.contains("VAT") || upper.contains("GST") || upper.contains("HST") ->
                RowKind.TAX
            upper.contains("TIP") || upper.contains("GRATUITY") || upper.contains("SERVICE CHARGE") ->
                RowKind.TIP
            upper.contains("TOTAL") || upper.contains("AMOUNT DUE") || upper.contains("BALANCE DUE") ->
                RowKind.TOTAL
            upper.contains("CASH") || upper.contains("CHANGE") || upper.contains("CARD") ||
                upper.contains("TENDER") || upper.contains("VISA") || upper.contains("MASTERCARD") ->
                RowKind.IGNORED
            else -> RowKind.ITEM
        }
    }

    /** The 0.02 arithmetic gate, in minor units. */
    const val TOLERANCE_MINOR = 2L
    private const val MINOR_PER_UNIT = 100L
    private const val MIN_BOXES = 3
    private const val ROW_OVERLAP_FRACTION = 0.5
    private const val COLUMN_TOLERANCE_FRACTION = 0.08
    private const val COLUMN_SEED_BAND = 12
}
