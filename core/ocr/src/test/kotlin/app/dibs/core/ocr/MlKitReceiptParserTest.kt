// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ocr

import app.dibs.core.domain.receipt.ReceiptFrame
import app.dibs.core.domain.receipt.ReceiptParser
import app.dibs.core.domain.receipt.TextBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitReceiptParserTest {

    private val receiptBoxes = listOf(
        TextBox("Latte", 20, 0, 70, 20),
        TextBox("4.50", 560, 0, 600, 20),
        TextBox("Bagel", 20, 30, 70, 50),
        TextBox("3.25", 560, 30, 600, 50),
        TextBox("Total", 20, 60, 70, 80),
        TextBox("7.75", 560, 60, 600, 80),
    )

    private fun engine(boxes: List<TextBox>, delayMillis: Long = 0) = object : OcrEngine {
        override suspend fun recognize(jpegBytes: ByteArray): List<TextBox> {
            delay(delayMillis)
            return boxes
        }
    }

    private val frame = ReceiptFrame(byteArrayOf(1))

    @Test
    fun `given recognized text then the parsed draft comes back with the gate verdict`() = runTest {
        val result = MlKitReceiptParser(engine(receiptBoxes)).parse(listOf(frame))
        val parsed = result as ReceiptParser.Result.Parsed
        assertEquals(2, parsed.draft.items.size)
        assertEquals(775L, parsed.draft.totalMinor)
        assertEquals(true, parsed.draft.gatePassed)
    }

    @Test
    fun `edge 46 - given recognition exceeding the ten second budget then it times out instead of hanging`() = runTest {
        val result = MlKitReceiptParser(engine(receiptBoxes, delayMillis = 60_000)).parse(listOf(frame))
        assertEquals(ReceiptParser.Result.TimedOut, result)
    }

    @Test
    fun `given an image with no usable text then the result is NothingFound, routing to manual entry`() = runTest {
        val result = MlKitReceiptParser(engine(emptyList())).parse(listOf(frame))
        assertEquals(ReceiptParser.Result.NothingFound, result)
    }

    @Test
    fun `given an engine crash then it is treated as no text, never propagated`() = runTest {
        val crashing = object : OcrEngine {
            override suspend fun recognize(jpegBytes: ByteArray): List<TextBox> = error("native crash")
        }
        val result = MlKitReceiptParser(crashing).parse(listOf(frame))
        assertEquals(ReceiptParser.Result.NothingFound, result)
    }

    @Test
    fun `given multiple frames then their drafts merge with boundary dedupe`() = runTest {
        val frame2Boxes = listOf(
            TextBox("Bagel", 20, 0, 70, 20),
            TextBox("3.25", 560, 0, 600, 20),
            TextBox("Soup", 20, 30, 70, 50),
            TextBox("5.00", 560, 30, 600, 50),
            TextBox("Total", 20, 60, 70, 80),
            TextBox("12.75", 555, 60, 600, 80),
        )
        val perFrame = mapOf(1.toByte() to receiptBoxes.dropLast(2), 2.toByte() to frame2Boxes)
        val engine = object : OcrEngine {
            override suspend fun recognize(jpegBytes: ByteArray): List<TextBox> =
                perFrame.getValue(jpegBytes[0])
        }

        val result = MlKitReceiptParser(engine)
            .parse(listOf(ReceiptFrame(byteArrayOf(1)), ReceiptFrame(byteArrayOf(2))))
        val parsed = result as ReceiptParser.Result.Parsed
        assertEquals(listOf("Latte", "Bagel", "Soup"), parsed.draft.items.map { it.label })
        assertEquals(1275L, parsed.draft.totalMinor)
        assertTrue(parsed.draft.gatePassed == true)
    }
}
