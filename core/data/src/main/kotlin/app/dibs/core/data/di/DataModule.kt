// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.di

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.key.AndroidKeystoreCipher
import app.dibs.core.data.key.DatabaseKeyProvider
import app.dibs.core.data.key.WrappedPassphraseStore
import app.dibs.core.data.ledger.DeviceIdProvider
import app.dibs.core.data.ledger.LedgerRepository
import app.dibs.core.data.settings.DataStoreSettingsStore
import app.dibs.core.data.settings.UserSettingsSerializer
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun databaseKeyProvider(@ApplicationContext context: Context): DatabaseKeyProvider = WrappedPassphraseStore(
        file = context.filesDir.resolve("dibs-db.key"),
        cipher = AndroidKeystoreCipher(),
    )

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, keyProvider: DatabaseKeyProvider): DibsDatabase =
        DibsDatabase.buildEncrypted(context, keyProvider)

    // DeviceId is a @JvmInline value class, which Dagger cannot bind directly;
    // the provider is the binding and the id is resolved where it's consumed.
    @Provides
    @Singleton
    fun deviceIdProvider(@ApplicationContext context: Context): DeviceIdProvider =
        DeviceIdProvider(context.filesDir.resolve("device-id"))

    @Provides
    @Singleton
    fun ledgerRepository(database: DibsDatabase, deviceIdProvider: DeviceIdProvider): LedgerRepository =
        LedgerRepository(database, deviceIdProvider.getOrCreate())

    @Provides
    @Singleton
    fun ledger(repository: LedgerRepository): Ledger = repository

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): SettingsStore = DataStoreSettingsStore(
        DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserSettings() },
        ) { context.filesDir.resolve("settings/user-settings.json") },
    )
}
