// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens

/**
 * The designed empty state every list must have (SPEC §4.2): one illustration
 * slot, one sentence of context, and the single clear action(s) — never a bare
 * "no items".
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.spacingXl + Dimens.spacingLg),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Dimens.spacingMd))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.spacingSm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.spacingLg))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            actions()
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun EmptyStatePreviewLight() {
    DibsTheme {
        EmptyState(title = "No groups yet", body = "Create a group to start splitting expenses.")
    }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun EmptyStatePreviewDark() {
    DibsTheme {
        EmptyState(title = "No groups yet", body = "Create a group to start splitting expenses.")
    }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun EmptyStatePreviewLargeFont() {
    DibsTheme {
        EmptyState(title = "No groups yet", body = "Create a group to start splitting expenses.")
    }
}
