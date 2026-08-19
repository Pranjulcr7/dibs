// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

/**
 * One piece of recognized text with its bounding box, in image pixels. The
 * OCR engine (or a test fixture's ground-truth annotation) produces these;
 * the layout parser consumes them. Pure data so the parser stays JVM-pure.
 */
data class TextBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/**
 * A parsed line item. [totalMinor] is the line total in minor units (may be
 * negative for discounts and coupons — edge 41). [quantity] comes from a
 * recognized "2 x" style prefix and defaults to 1.
 */
data class ReceiptItem(
    val label: String,
    val quantity: Int = 1,
    val totalMinor: Long,
)

/** Things the parser wants the review screen to surface (FR-R4). */
enum class ReceiptFlag {
    /** Recognition produced too little text to work with (blur, glare, handwriting — edges 34/37). */
    LOW_TEXT,

    /** A total was found but no line items — degrade to a single-amount expense (edge 43). */
    TOTAL_ONLY,

    /** Items ± tax/tip don't add up to the printed total (edges 39/40). */
    ARITHMETIC_MISMATCH,

    /** Monetary rows existed that the parser could not classify — regions need manual entry (edge 36). */
    UNPARSED_REGIONS,
}

/**
 * The parser's best effort, always destined for the mandatory review screen
 * (FR-R3) — this is never committed as an expense without human eyes on it.
 */
data class ReceiptDraft(
    val items: List<ReceiptItem> = emptyList(),
    val subtotalMinor: Long? = null,
    val taxMinor: Long? = null,
    val tipMinor: Long? = null,
    val totalMinor: Long? = null,
    /** Null when there was no printed total to check against. */
    val gatePassed: Boolean? = null,
    val flags: Set<ReceiptFlag> = emptySet(),
) {
    val itemsSumMinor: Long get() = items.sumOf { it.totalMinor }
}

/** A single captured frame, kept opaque (JPEG bytes) so the domain stays pure. */
class ReceiptFrame(val jpegBytes: ByteArray)

/**
 * The swappable OCR tier (CLAUDE.md): features see only this. Implementations
 * live in `:core:ocr`; the app must run with [NoOpReceiptParser] alone.
 */
interface ReceiptParser {
    /** Never throws: failures come back as [Result.NothingFound] or [Result.TimedOut]. */
    suspend fun parse(frames: List<ReceiptFrame>): Result

    sealed interface Result {
        data class Parsed(val draft: ReceiptDraft) : Result

        /** Nothing usable in the image — the UI offers retake advice and manual entry (edge 34). */
        data object NothingFound : Result

        /** Recognition exceeded the 10s budget and was cancelled (edge 46). */
        data object TimedOut : Result
    }
}

/** The always-available tier: scan simply isn't offered. Manual entry is primary regardless. */
object NoOpReceiptParser : ReceiptParser {
    override suspend fun parse(frames: List<ReceiptFrame>): ReceiptParser.Result = ReceiptParser.Result.NothingFound
}
