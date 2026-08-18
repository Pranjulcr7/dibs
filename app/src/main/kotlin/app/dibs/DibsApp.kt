// SPDX-License-Identifier: Apache-2.0
package app.dibs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.groups.ActivityScreen
import app.dibs.feature.groups.GroupsScreen
import app.dibs.feature.settings.SettingsScreen
import app.dibs.feature.settings.onboarding.OnboardingRoute
import kotlinx.coroutines.launch

private enum class HomeTab(@StringRes val label: Int, val icon: ImageVector) {
    GROUPS(R.string.tab_groups, Icons.Filled.Home),
    ACTIVITY(R.string.tab_activity, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

/**
 * The whole navigation shell. The onboarding gate is state-driven: completing
 * onboarding updates settings, which recomposes straight into home — no
 * navigation event to lose on process death (edge 61 mindset from day one).
 */
@Composable
fun DibsApp(settings: UserSettings) {
    DibsTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        if (!settings.onboardingComplete) {
            OnboardingRoute(onDone = {})
        } else {
            HomeScaffold()
        }
    }
}

@Composable
private fun HomeScaffold() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(R.string.coming_soon)
    val notYet: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(comingSoon) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (HomeTab.entries[selectedTab] == HomeTab.GROUPS) {
                FloatingActionButton(onClick = notYet) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.fab_add_expense),
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (HomeTab.entries[selectedTab]) {
            HomeTab.GROUPS -> GroupsScreen(
                onCreateGroup = notYet,
                onImportFromSplitwise = notYet,
                modifier = contentModifier,
            )
            HomeTab.ACTIVITY -> ActivityScreen(modifier = contentModifier)
            HomeTab.SETTINGS -> SettingsScreen(modifier = contentModifier)
        }
    }
}
