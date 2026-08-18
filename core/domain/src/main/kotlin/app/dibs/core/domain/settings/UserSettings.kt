// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.settings

import app.dibs.core.domain.money.CurrencyCode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** FR-T1 theme choice; SYSTEM follows the OS setting. */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Everything the user has told the app about themselves and their preferences.
 *
 * Why this lives in the domain: feature modules may depend only on
 * `:core:domain` (CLAUDE.md architecture), so the type every settings-aware
 * screen consumes must be defined here, with storage (`DataStore` in
 * `:core:data`) behind [SettingsStore]. Defaults are the values a fresh install
 * ships with — chosen so the app is fully usable before onboarding writes
 * anything (SPEC §1: degrade, don't fail).
 */
@Serializable
data class UserSettings(
    val onboardingComplete: Boolean = false,
    val displayName: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultCurrency: CurrencyCode = CurrencyCode("USD"),
)

/**
 * Read/update access to [UserSettings]. An interface so features stay off the
 * Android storage stack and tests can substitute an in-memory store.
 */
interface SettingsStore {

    /** Emits the current settings and every subsequent change. */
    val settings: Flow<UserSettings>

    /**
     * Atomically transforms the stored settings. Transform-based (not setter
     * per field) so concurrent writers can never interleave half-updates.
     */
    suspend fun update(transform: (UserSettings) -> UserSettings)
}
