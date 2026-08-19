// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ocr

import android.graphics.BitmapFactory
import app.dibs.core.domain.receipt.TextBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit Text Recognition v2, bundled Latin model — fully on-device, nothing
 * downloaded, nothing uploaded (invariant 3). Latin script only: non-Latin
 * receipts are documented as unsupported (edge 38) rather than producing
 * garbage. Recognition granularity is the *line*, which matches the layout
 * parser's row-grouping input.
 */
class MlKitOcrEngine : OcrEngine {

    override suspend fun recognize(jpegBytes: ByteArray): List<TextBox> {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                TextBox(line.text, box.left, box.top, box.right, box.bottom)
            }
    }
}
