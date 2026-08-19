// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ocr

import app.dibs.core.domain.receipt.TextBox

/**
 * The raw text-recognition seam. [MlKitOcrEngine] is the production
 * implementation; tests substitute a fake so the parser wrapper's timeout,
 * merge, and failure logic run on the JVM without Play services.
 */
interface OcrEngine {
    /** Recognized text boxes for one JPEG frame; empty when nothing was found. */
    suspend fun recognize(jpegBytes: ByteArray): List<TextBox>
}
