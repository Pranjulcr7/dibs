// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ocr.di

import app.dibs.core.domain.receipt.ReceiptParser
import app.dibs.core.ocr.MlKitOcrEngine
import app.dibs.core.ocr.MlKitReceiptParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the shipping parser tier. Features inject only [ReceiptParser]; the
 * app still builds and runs with `NoOpReceiptParser` if this module is
 * dropped (the planned F-Droid `foss` flavor does exactly that).
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {

    @Provides
    @Singleton
    fun receiptParser(): ReceiptParser = MlKitReceiptParser(MlKitOcrEngine())
}
