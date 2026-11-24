// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    private val link = "dibs://join?g=11111111-0000-4000-8000-000000000000" +
        "&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
        "&i=22222222-0000-4000-8000-000000000000" +
        "&sig=" + "A".repeat(86)

    /** A camera-style luminance plane: [stride] bytes per row, black modules dark, the rest light. */
    private fun frame(text: String, size: Int, stride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(stride * size) { i ->
            val x = i % stride
            val y = i / stride
            if (x < size && matrix.get(x, y)) DARK else LIGHT
        }
    }

    @Test
    fun `given a frame showing an invite code then the exact link text is returned`() {
        assertEquals(link, QrDecoder.decode(frame(link, SIZE, SIZE), SIZE, SIZE, SIZE))
    }

    @Test
    fun `given a frame with row padding, as real cameras deliver, then decoding still works`() {
        val stride = SIZE + 24

        assertEquals(link, QrDecoder.decode(frame(link, SIZE, stride), stride, SIZE, SIZE))
    }

    @Test
    fun `given a frame with no code then the result is null, not an exception`() {
        assertNull(QrDecoder.decode(ByteArray(SIZE * SIZE) { LIGHT }, SIZE, SIZE, SIZE))
        assertNull(QrDecoder.decode(ByteArray(SIZE * SIZE) { (it * 31 % 251).toByte() }, SIZE, SIZE, SIZE))
    }

    @Test
    fun `given impossible frame geometry then the result is null, not a crash`() {
        assertNull(QrDecoder.decode(ByteArray(10), rowStride = SIZE, width = SIZE, height = SIZE))
        assertNull(QrDecoder.decode(ByteArray(0), rowStride = 0, width = 0, height = 0))
        assertNull(QrDecoder.decode(ByteArray(SIZE * SIZE), rowStride = SIZE - 1, width = SIZE, height = SIZE))
    }

    private companion object {
        const val SIZE = 400
        const val DARK: Byte = 0
        const val LIGHT: Byte = -1 // 0xFF
    }
}
