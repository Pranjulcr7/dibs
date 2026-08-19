// SPDX-License-Identifier: Apache-2.0
package app.dibs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.expense.editor.ExpenseEditorRoute
import app.dibs.feature.groups.ActivityRoute
import app.dibs.feature.groups.GroupsRoute
import app.dibs.feature.groups.create.CreateGroupRoute
import app.dibs.feature.groups.detail.GroupDetailRoute
import app.dibs.feature.groups.members.MembersRoute
import app.dibs.feature.settings.SettingsRoute
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
fun DibsApp(settings: UserSettings, initialRoute: String? = null) {
    DibsTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        if (!settings.onboardingComplete) {
            OnboardingRoute(onDone = {})
        } else {
            DibsNavHost(initialRoute)
        }
    }
}

@Composable
private fun DibsNavHost(initialRoute: String? = null) {
    val navController = rememberNavController()
    // A recurring-expense notification lands here with a prefilled-editor route.
    androidx.compose.runtime.LaunchedEffect(initialRoute) {
        if (initialRoute != null) navController.navigate(initialRoute)
    }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScaffold(
                onOpenGroup = { navController.navigate("group/${it.value}") },
                onCreateGroup = { navController.navigate("create-group") },
                onImport = { navController.navigate("import") },
                onAddExpense = { groupId -> navController.navigate("group/${groupId.value}/expense/new") },
                onOpenExpense = { groupId, expenseId ->
                    navController.navigate("group/${groupId.value}/expense/${expenseId.value}")
                },
            )
        }
        composable("import") {
            app.dibs.feature.groups.importer.ImportRoute(
                onDone = { groupId ->
                    navController.navigate("group/${groupId.value}") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("create-group") {
            CreateGroupRoute(
                onCreated = { groupId ->
                    navController.navigate("group/${groupId.value}") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            "group/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { entry ->
            val groupId = entry.arguments?.getString("groupId").orEmpty()
            GroupDetailRoute(
                onBack = { navController.popBackStack() },
                onOpenMembers = { navController.navigate("group/$groupId/members") },
                onAddExpense = { navController.navigate("group/$groupId/expense/new") },
                onOpenExpense = { navController.navigate("group/$groupId/expense/${it.value}") },
                onDuplicateExpense = { navController.navigate("group/$groupId/expense/new?duplicateOf=${it.value}") },
                onOpenTrash = { navController.navigate("group/$groupId/trash") },
            )
        }
        composable(
            "group/{groupId}/trash",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) {
            app.dibs.feature.groups.trash.TrashRoute(onBack = { navController.popBackStack() })
        }
        composable(
            "group/{groupId}/expense/new?duplicateOf={duplicateOf}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("duplicateOf") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ExpenseEditorRoute(onDone = { navController.popBackStack() })
        }
        composable(
            "group/{groupId}/expense/{expenseId}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("expenseId") { type = NavType.StringType },
            ),
        ) {
            ExpenseEditorRoute(onDone = { navController.popBackStack() })
        }
        composable(
            "group/{groupId}/members",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) {
            MembersRoute(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScaffold(
    onOpenGroup: (app.dibs.core.domain.model.GroupId) -> Unit,
    onCreateGroup: () -> Unit,
    onAddExpense: (app.dibs.core.domain.model.GroupId) -> Unit,
    onOpenExpense: (app.dibs.core.domain.model.GroupId, app.dibs.core.domain.model.ExpenseId) -> Unit,
    onImport: () -> Unit,
    quickAdd: QuickAddViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showGroupPicker by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val targets by quickAdd.targets.collectAsState()

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
                FloatingActionButton(onClick = {
                    when {
                        targets.isEmpty() -> onCreateGroup()
                        targets.size == 1 -> onAddExpense(targets.single().id)
                        else -> showGroupPicker = true
                    }
                }) {
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
            HomeTab.GROUPS -> GroupsRoute(
                onOpenGroup = onOpenGroup,
                onCreateGroup = onCreateGroup,
                onImportFromSplitwise = onImport,
                onBackUp = { selectedTab = HomeTab.SETTINGS.ordinal },
                modifier = contentModifier,
            )
            HomeTab.ACTIVITY -> ActivityRoute(
                onOpenExpense = onOpenExpense,
                modifier = contentModifier,
            )
            HomeTab.SETTINGS -> SettingsRoute(modifier = contentModifier)
        }
    }

    if (showGroupPicker) {
        val oneOffName = stringResource(R.string.quick_add_one_off_name)
        val selfFallback = stringResource(R.string.quick_add_self_fallback)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showGroupPicker = false }) {
            androidx.compose.foundation.layout.Column(Modifier.padding(app.dibs.core.ui.theme.Dimens.spacingMd)) {
                Text(
                    stringResource(R.string.quick_add_pick_group),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                targets.forEach { target ->
                    androidx.compose.material3.TextButton(onClick = {
                        showGroupPicker = false
                        onAddExpense(target.id)
                    }) {
                        Text(target.label)
                    }
                }
                // FR-G5: a hidden ad-hoc group, same code path as everything else.
                androidx.compose.material3.TextButton(onClick = {
                    showGroupPicker = false
                    scope.launch {
                        onAddExpense(quickAdd.createAdHocGroup(oneOffName, selfFallback))
                    }
                }) {
                    Text(stringResource(R.string.quick_add_just_once))
                }
                androidx.compose.foundation.layout.Spacer(
                    Modifier.padding(app.dibs.core.ui.theme.Dimens.spacingLg),
                )
            }
        }
    }
}
