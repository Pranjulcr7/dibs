// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in one camera frame's luminance plane and returns its text.
 *
 * Pure JVM and free software (zxing), so it runs in both flavors and is
 * tested without a camera. Returns null for "no code in this frame" — by far
 * the common case while the user is still aiming — and for anything
 * undecodable; a scanner must never crash on what it is pointed at.
 */
internal object QrDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * [luminance] is the Y plane as the camera delivers it: [rowStride] bytes
     * per row, of which the first [width] are pixels.
     */
    @Suppress("SwallowedException") // "not found" and "unreadable" both mean: keep looking
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val plausible = width > 0 && height > 0 && rowStride >= width
        // The last row may be delivered without its padding, hence (height - 1) full rows + one width.
        if (!plausible || luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ReaderException) {
            // Checksum or format failure on a partial/blurred code.
            null
        }
    }
}
