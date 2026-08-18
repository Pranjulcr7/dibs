// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.theme.DibsTheme

/**
 * Stage 3a: the designed empty state (SPEC §4.1 screen 1 — create and import
 * side by side). The group list itself arrives with group data in stage 3b;
 * this screen keeps its stateless shape so that change is additive.
 */
@Composable
fun GroupsScreen(onCreateGroup: () -> Unit, onImportFromSplitwise: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.groups_empty_title),
        body = stringResource(R.string.groups_empty_body),
        modifier = modifier.fillMaxSize(),
    ) {
        Button(onClick = onCreateGroup) {
            Text(stringResource(R.string.groups_create))
        }
        OutlinedButton(onClick = onImportFromSplitwise) {
            Text(stringResource(R.string.groups_import_splitwise))
        }
    }
}

/** Stage 3a placeholder: the cross-group feed fills in once expenses exist (3c). */
@Composable
fun ActivityScreen(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.activity_empty_title),
        body = stringResource(R.string.activity_empty_body),
        modifier = modifier.fillMaxSize(),
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun GroupsPreviewLight() {
    DibsTheme { GroupsScreen(onCreateGroup = {}, onImportFromSplitwise = {}) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun GroupsPreviewDark() {
    DibsTheme { GroupsScreen(onCreateGroup = {}, onImportFromSplitwise = {}) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun GroupsPreviewLargeFont() {
    DibsTheme { GroupsScreen(onCreateGroup = {}, onImportFromSplitwise = {}) }
}

@Preview(name = "Activity light", showBackground = true)
@Composable
private fun ActivityPreviewLight() {
    DibsTheme { ActivityScreen() }
}

@Preview(name = "Activity dark", showBackground = true, uiMode = 0x21)
@Composable
private fun ActivityPreviewDark() {
    DibsTheme { ActivityScreen() }
}

@Preview(name = "Activity large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun ActivityPreviewLargeFont() {
    DibsTheme { ActivityScreen() }
}
