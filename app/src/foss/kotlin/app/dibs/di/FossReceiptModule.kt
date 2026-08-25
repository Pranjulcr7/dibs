// SPDX-License-Identifier: Apache-2.0
package app.dibs.di

import app.dibs.core.domain.receipt.NoOpReceiptParser
import app.dibs.core.domain.receipt.ReceiptParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The FOSS flavor's parser binding (OPEN_SOURCE_SETUP §7): no ML Kit, no
 * bundled model. Scanning degrades exactly along the designed fallback path —
 * "Read receipt" reports nothing found and the capture screen's gallery and
 * type-it-in routes carry the flow — so every other feature is byte-for-byte
 * the Play build.
 */
@Module
@InstallIn(SingletonComponent::class)
object FossReceiptModule {

    @Provides
    @Singleton
    fun receiptParser(): ReceiptParser = NoOpReceiptParser
}
