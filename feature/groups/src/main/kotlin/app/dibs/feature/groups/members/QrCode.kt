// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [text] as a QR code entirely on the device (zxing, pure Java, present
 * in both flavors). An invite link carries the group's key, so it must never
 * be sent to a QR-rendering service — there is no network path here at all.
 */
internal fun qrBitmap(text: String, pixels: Int = QR_PIXELS): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixels, pixels)
    val colors = IntArray(pixels * pixels) { i ->
        if (matrix.get(i % pixels, i / pixels)) Color.BLACK else Color.WHITE
    }
    val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.RGB_565)
    bitmap.setPixels(colors, 0, pixels, 0, 0, pixels, pixels)
    return bitmap.asImageBitmap()
}

private const val QR_PIXELS = 512
