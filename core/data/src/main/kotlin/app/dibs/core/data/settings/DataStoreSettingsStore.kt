// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore serializer for [UserSettings] as JSON via kotlinx-serialization.
 *
 * Why JSON and not protobuf codegen (which CLAUDE.md's stack table names):
 * identical DataStore atomicity and typed access without a second code
 * generator in the build; unknown fields are ignored so downgrade reads don't
 * crash. Recorded in DECISIONS.md D21.
 */
object UserSettingsSerializer : Serializer<UserSettings> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: UserSettings = UserSettings()

    override suspend fun readFrom(input: InputStream): UserSettings = try {
        json.decodeFromString<UserSettings>(input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        // Surfaced as CorruptionException so DataStore's corruption handler
        // replaces the file with defaults instead of the app crash-looping.
        throw CorruptionException("Settings file is not valid JSON", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Settings file contains invalid values", e)
    }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

/** The production [SettingsStore]: a thin adapter over DataStore. */
class DataStoreSettingsStore(
    private val dataStore: DataStore<UserSettings>,
) : SettingsStore {

    override val settings: Flow<UserSettings> = dataStore.data

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        dataStore.updateData(transform)
    }
}
