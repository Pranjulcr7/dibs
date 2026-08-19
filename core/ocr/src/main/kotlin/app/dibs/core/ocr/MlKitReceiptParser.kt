// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ocr

import app.dibs.core.domain.receipt.ReceiptFlag
import app.dibs.core.domain.receipt.ReceiptFrame
import app.dibs.core.domain.receipt.ReceiptParser
import app.dibs.core.domain.receipt.RuleBasedLayoutParser
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The shipping [ReceiptParser] tier: OCR each frame through [engine], fold
 * the boxes through the pure [RuleBasedLayoutParser], merge frames (FR-R7).
 * The whole run sits under a hard 10s budget — blowing it cancels cleanly
 * and reports [ReceiptParser.Result.TimedOut] so the UI can route to manual
 * entry (edge 46). Never throws.
 */
class MlKitReceiptParser(
    private val engine: OcrEngine,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : ReceiptParser {

    override suspend fun parse(frames: List<ReceiptFrame>): ReceiptParser.Result {
        if (frames.isEmpty()) return ReceiptParser.Result.NothingFound
        val drafts = withTimeoutOrNull(timeoutMillis) {
            frames.map { frame ->
                RuleBasedLayoutParser.parse(recognizeOrEmpty(frame.jpegBytes))
            }
        } ?: return ReceiptParser.Result.TimedOut

        val merged = RuleBasedLayoutParser.merge(drafts)
        val unusable = merged.items.isEmpty() && merged.totalMinor == null
        return if (unusable && ReceiptFlag.LOW_TEXT in merged.flags) {
            ReceiptParser.Result.NothingFound
        } else {
            ReceiptParser.Result.Parsed(merged)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // Any engine failure means "no text", never a crash; cancellation must
    // still propagate so the timeout works.
    private suspend fun recognizeOrEmpty(jpegBytes: ByteArray) = try {
        engine.recognize(jpegBytes)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (engineFailure: Exception) {
        emptyList()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
