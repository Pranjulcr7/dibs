// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens

private val CurrencyChoices = listOf(
    "USD", "EUR", "GBP", "INR", "JPY", "KRW", "AUD", "CAD", "CHF", "BRL", "MXN", "KWD", "BHD", "AED", "SGD",
)

@Composable
fun SettingsRoute(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    settings?.let { current ->
        SettingsScreen(
            settings = current,
            onThemeMode = viewModel::setThemeMode,
            onDynamicColor = viewModel::setDynamicColor,
            onDisplayName = viewModel::setDisplayName,
            onDefaultCurrency = viewModel::setDefaultCurrency,
            onAppLock = viewModel::setAppLock,
            onBlockScreenshots = viewModel::setBlockScreenshots,
            modifier = modifier,
        )
    }
}

/** FR-T1/T2/T3/T6 + edge 73. Storage and parser management arrive with M4/M5. */
@Suppress("LongParameterList", "LongMethod") // a settings form is one callback per setting
@Composable
fun SettingsScreen(
    settings: UserSettings,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDisplayName: (String) -> Unit,
    onDefaultCurrency: (CurrencyCode) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionHeader(R.string.settings_section_appearance)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                                    ThemeMode.LIGHT -> R.string.settings_theme_light
                                    ThemeMode.DARK -> R.string.settings_theme_dark
                                },
                            ),
                        )
                    },
                    modifier = Modifier.testTag("theme-${mode.name.lowercase()}"),
                )
            }
        }
        ToggleRow(
            label = stringResource(R.string.settings_dynamic_color),
            checked = settings.dynamicColor,
            onChange = onDynamicColor,
            tag = "dynamic-color",
        )

        SectionHeader(R.string.settings_section_defaults)
        var name by rememberSaveable(settings.displayName) { mutableStateOf(settings.displayName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_display_name)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("display-name"),
        )
        LaunchedEffect(name) {
            if (name != settings.displayName) {
                kotlinx.coroutines.delay(NAME_DEBOUNCE_MILLIS)
                onDisplayName(name)
            }
        }
        Text(stringResource(R.string.settings_default_currency), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
        ) {
            CurrencyChoices.forEach { code ->
                FilterChip(
                    selected = settings.defaultCurrency.value == code,
                    onClick = { onDefaultCurrency(CurrencyCode(code)) },
                    label = { Text(code) },
                )
            }
        }

        SectionHeader(R.string.settings_section_privacy)
        ToggleRow(
            label = stringResource(R.string.settings_app_lock),
            hint = stringResource(R.string.settings_app_lock_hint),
            checked = settings.appLockEnabled,
            onChange = onAppLock,
            tag = "app-lock",
        )
        ToggleRow(
            label = stringResource(R.string.settings_block_screenshots),
            hint = stringResource(R.string.settings_block_screenshots_hint),
            checked = settings.blockScreenshots,
            onChange = onBlockScreenshots,
            tag = "block-screenshots",
        )

        SectionHeader(R.string.settings_section_about)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.spacingMd)) {
                Text(
                    stringResource(R.string.settings_about_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Dimens.spacingSm))
                Text(
                    stringResource(R.string.settings_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Dimens.spacingXl))
    }
}

@Composable
private fun SectionHeader(labelRes: Int) {
    Spacer(Modifier.height(Dimens.spacingSm))
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    hint: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

private const val NAME_DEBOUNCE_MILLIS = 600L

@androidx.compose.ui.tooling.preview.Preview(name = "Light", showBackground = true)
@Composable
private fun SettingsPreviewLight() {
    DibsTheme { SettingsScreen(UserSettings(displayName = "Priya"), {}, {}, {}, {}, {}, {}) }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun SettingsPreviewDark() {
    DibsTheme { SettingsScreen(UserSettings(), {}, {}, {}, {}, {}, {}) }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun SettingsPreviewLargeFont() {
    DibsTheme { SettingsScreen(UserSettings(), {}, {}, {}, {}, {}, {}) }
}
