// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.settings

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
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
    /**
     * Which member is "you", per group. Device-local by design: the op-log
     * records members, but *which one is me* differs per device, so identity
     * mapping never belongs in shared state (DECISIONS.md D23).
     */
    val selfMembers: Map<GroupId, MemberId> = emptyMap(),
    /** FR-T3: biometric/credential gate on app open. */
    val appLockEnabled: Boolean = false,
    /** Edge 73: FLAG_SECURE, opt-in. */
    val blockScreenshots: Boolean = false,
    /**
     * FR-E7 recurrence rules — device-local reminders (D33): a rule prompts a
     * notification that opens a prefilled editor; nothing is ever auto-created.
     */
    val recurringRules: List<RecurringRule> = emptyList(),
)

@Serializable
data class RecurringRule(
    val id: String,
    val groupId: GroupId,
    val templateExpenseId: ExpenseId,
    val periodDays: Int,
    val nextDueEpochDay: Long,
)

/** Pure due-rule math so the WorkManager worker stays a thin shell. */
object RecurringRules {

    /** Rules due on or before [todayEpochDay]. */
    fun due(rules: List<RecurringRule>, todayEpochDay: Long): List<RecurringRule> =
        rules.filter { it.nextDueEpochDay <= todayEpochDay }

    /** Advances fired rules past today; a long-dead app catches up without stacking prompts. */
    fun advance(rules: List<RecurringRule>, firedIds: Set<String>, todayEpochDay: Long): List<RecurringRule> =
        rules.map { rule ->
            if (rule.id in firedIds) {
                var next = rule.nextDueEpochDay
                while (next <= todayEpochDay) next += rule.periodDays
                rule.copy(nextDueEpochDay = next)
            } else {
                rule
            }
        }
}

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
