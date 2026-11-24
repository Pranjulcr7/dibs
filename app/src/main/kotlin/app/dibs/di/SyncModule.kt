// SPDX-License-Identifier: Apache-2.0
package app.dibs.di

import app.dibs.core.data.ledger.DeviceIdProvider
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.SyncMetaStore
import app.dibs.core.domain.sync.SyncTransport
import app.dibs.core.sync.ManualFileTransport
import app.dibs.core.sync.SyncService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Composition root for sync tier 1 (D41): manual transport, bundle service. */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun syncTransport(): SyncTransport = ManualFileTransport()

    @Provides
    @Singleton
    fun syncService(
        ledger: Ledger,
        keys: GroupKeyStore,
        meta: SyncMetaStore,
        identity: IdentitySource,
        deviceIdProvider: DeviceIdProvider,
    ): SyncService = SyncService(ledger, keys, meta, identity, deviceIdProvider.getOrCreate())

    @Provides
    @Singleton
    fun bundleSync(service: SyncService): BundleSync = service
}
