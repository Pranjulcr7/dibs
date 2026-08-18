// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens

/**
 * Placeholder for stage 3a: the real settings surface (theme, currency, app
 * lock, storage) lands in stage 3d. The privacy statement ships from day one —
 * it is a product commitment (FR-T6), not a feature.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spacingMd),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(Dimens.spacingMd))
        Text(
            text = stringResource(R.string.settings_placeholder_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.spacingLg))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_about_privacy),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(Dimens.spacingMd),
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SettingsPreviewLight() {
    DibsTheme { SettingsScreen() }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun SettingsPreviewDark() {
    DibsTheme { SettingsScreen() }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun SettingsPreviewLargeFont() {
    DibsTheme { SettingsScreen() }
}
