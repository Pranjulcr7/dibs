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
@Suppress("TooManyFunctions") // one small named rule per function beats fewer, longer ones
object RuleBasedLayoutParser {

    fun parse(boxes: List<TextBox>): ReceiptDraft {
        if (boxes.size < MIN_BOXES) {
            return ReceiptDraft(flags = setOf(ReceiptFlag.LOW_TEXT))
        }

        val rows = groupIntoRows(boxes)
        // Two-pass price detection: strict two-decimal prices first (Western
        // receipts). When the receipt clearly isn't printing cents — fewer
        // than two decimal prices — fall back to integer mode for
        // zero-decimal currencies (JPY, IDR, KRW: "25,000", "91000"), a gap
        // the SROIE/CORD layout corpus exposed.
        val decimalPriced = rows.mapNotNull { row -> pricedRowOf(row, integerMode = false) }
        val priced = if (decimalPriced.size >= MIN_PRICED_ROWS) {
            decimalPriced
        } else {
            rows.mapNotNull { row -> pricedRowOf(row, integerMode = true) }
        }
        if (priced.isEmpty()) {
            return ReceiptDraft(flags = setOf(ReceiptFlag.LOW_TEXT))
        }

        val column = priceColumnRight(priced)
        val pageWidth = boxes.maxOf { it.right } - boxes.minOf { it.left }
        val tolerance = (pageWidth * COLUMN_TOLERANCE_FRACTION).toInt().coerceAtLeast(1)
        val monetary = priced.filter { abs(it.priceBox.right - column) <= tolerance }

        return accumulate(monetary)
    }

    private class Accumulator {
        var subtotal: Long? = null
        var tax: Long? = null
        var tip: Long? = null
        var total: Long? = null
        val items = mutableListOf<ReceiptItem>()
        var unparsed = false
        var summarySeen = false
    }

    private fun accumulate(monetary: List<PricedRow>): ReceiptDraft {
        val acc = Accumulator()
        monetary.forEach { row -> apply(acc, row) }

        val flags = mutableSetOf<ReceiptFlag>()
        if (acc.unparsed) flags += ReceiptFlag.UNPARSED_REGIONS
        if (acc.items.isEmpty() && acc.total != null) flags += ReceiptFlag.TOTAL_ONLY
        if (acc.items.isEmpty() && acc.total == null) flags += ReceiptFlag.LOW_TEXT

        val gate = gate(acc.items, acc.subtotal, acc.tax, acc.tip, acc.total)
        if (gate == false) flags += ReceiptFlag.ARITHMETIC_MISMATCH

        return ReceiptDraft(
            items = acc.items,
            subtotalMinor = acc.subtotal,
            taxMinor = acc.tax,
            tipMinor = acc.tip,
            totalMinor = acc.total,
            gatePassed = gate,
            flags = flags,
        )
    }

    private fun apply(acc: Accumulator, row: PricedRow) {
        val label = row.label.trim()
        when (classify(label)) {
            RowKind.SUBTOTAL -> {
                acc.subtotal = row.amountMinor
                acc.summarySeen = true
            }
            RowKind.TAX -> {
                acc.tax = (acc.tax ?: 0L) + row.amountMinor
                acc.summarySeen = true
            }
            RowKind.TIP -> {
                acc.tip = row.amountMinor
                acc.summarySeen = true
            }
            RowKind.TOTAL -> {
                // The last total wins: receipts often print running totals.
                acc.total = row.amountMinor
                acc.summarySeen = true
            }
            RowKind.IGNORED -> Unit
            RowKind.DISCOUNT ->
                // Printed positive, means subtract ("DISCOUNT 19,400").
                // Modeled as a negative item so the gate math holds and tax
                // proration sees the reduced subtotal (edge 41).
                acc.items += ReceiptItem(label = label, totalMinor = -abs(row.amountMinor))
            RowKind.ITEM ->
                if (acc.summarySeen) {
                    // A price row after the summary block that we can't
                    // name — flag it instead of guessing (edge 36).
                    acc.unparsed = true
                } else {
                    acc.items += itemOf(label, row.amountMinor)
                }
        }
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

    /** Re-runs the arithmetic gate after review-screen edits (FR-R4). */
    fun withRecomputedGate(draft: ReceiptDraft): ReceiptDraft {
        val gate = gate(draft.items, draft.subtotalMinor, draft.taxMinor, draft.tipMinor, draft.totalMinor)
        val flags = draft.flags.toMutableSet()
        flags.remove(ReceiptFlag.ARITHMETIC_MISMATCH)
        if (gate == false) flags += ReceiptFlag.ARITHMETIC_MISMATCH
        return draft.copy(gatePassed = gate, flags = flags)
    }

    private fun ReceiptItem.key() = label.lowercase() to totalMinor

    private fun gate(items: List<ReceiptItem>, subtotal: Long?, tax: Long?, tip: Long?, total: Long?): Boolean? {
        if (total == null || items.isEmpty()) return null
        val itemsSum = items.sumOf { it.totalMinor }
        val expected = itemsSum + (tax ?: 0L) + (tip ?: 0L)
        val totalOk = abs(expected - total) <= TOLERANCE_MINOR
        // A printed subtotal may sit before or after discount lines, so it may
        // equal either the net item sum or the positive-items sum.
        val positiveSum = items.filter { it.totalMinor > 0 }.sumOf { it.totalMinor }
        val subtotalOk = subtotal == null ||
            abs(itemsSum - subtotal) <= TOLERANCE_MINOR ||
            abs(positiveSum - subtotal) <= TOLERANCE_MINOR
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

    private fun pricedRowOf(row: List<TextBox>, integerMode: Boolean): PricedRow? {
        fun priceOf(text: String): Long? = parsePrice(text) ?: if (integerMode) parseIntegerPrice(text) else null
        val priceBox = row.lastOrNull { priceOf(it.text) != null } ?: return null
        val amount = priceOf(priceBox.text) ?: return null
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

    private val INTEGER_PRICE =
        Regex("""^\(?-?(?:Rp\.?|[$€£¥])?\s?(\d{1,3}(?:[.,]\d{3})+|\d{3,9})\)?-?$""")

    /**
     * Zero-decimal-currency prices: grouped ("25,000", "60.000") or bare
     * ("91000", min three digits so quantity columns can't match). Minor
     * units equal the printed value — exponent-0 semantics.
     */
    internal fun parseIntegerPrice(raw: String): Long? {
        val text = raw.trim()
        val match = INTEGER_PRICE.matchEntire(text) ?: return null
        val negative = text.startsWith("(") || text.contains('-')
        val value = match.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: return null
        return if (negative) -value else value
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

    private enum class RowKind { ITEM, SUBTOTAL, TAX, TIP, TOTAL, DISCOUNT, IGNORED }

    /** Ordered: the first kind with a matching keyword wins (SUBTOTAL before TOTAL matters). */
    private val KEYWORDS = listOf(
        RowKind.SUBTOTAL to listOf("SUBTOTAL", "SUB-TOTAL", "SUB TOTAL", "SUB_TOTAL", "SUBTTL"),
        RowKind.TAX to listOf("TAX", "VAT", "GST", "HST"),
        RowKind.TIP to listOf("TIP", "GRATUITY", "SERVICE CHARGE"),
        RowKind.TOTAL to listOf("TOTAL", "AMOUNT DUE", "BALANCE DUE"),
        RowKind.DISCOUNT to listOf("DISC", "COUPON", "PROMO SAVING"),
        RowKind.IGNORED to listOf(
            "CASH", "CHANGE", "CARD", "TENDER", "VISA", "MASTERCARD",
            "TUNAI", "KEMBALI", "QRIS", "DEBIT", "EDC", "ROUNDING",
        ),
    )

    private fun classify(label: String): RowKind {
        val upper = label.uppercase()
        return KEYWORDS.firstOrNull { (_, words) -> words.any(upper::contains) }?.first ?: RowKind.ITEM
    }

    /** The 0.02 arithmetic gate, in minor units. */
    const val TOLERANCE_MINOR = 2L
    private const val MINOR_PER_UNIT = 100L
    private const val MIN_BOXES = 3
    private const val MIN_PRICED_ROWS = 2
    private const val ROW_OVERLAP_FRACTION = 0.5
    private const val COLUMN_TOLERANCE_FRACTION = 0.08
    private const val COLUMN_SEED_BAND = 12
}
