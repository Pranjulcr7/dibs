// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.sync.SyncStatus
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.groups.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GroupDetailRoute(
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onAddExpense: () -> Unit,
    onOpenExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onDuplicateExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onOpenTrash: () -> Unit,
    onScanReceipt: () -> Unit,
    deletedExpenseId: String? = null,
    onDeletedShown: () -> Unit = {},
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = viewModel.exportCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }
    val importSyncLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) viewModel.importSyncBundle(bytes)
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.syncEvents.collect { notice ->
            snackbarHostState.showSnackbar(notice.outcome.message(context))
            notice.newlyJoined.forEach { name ->
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.sync_joined_notice, name),
                    actionLabel = context.getString(R.string.sync_joined_notice_action),
                )
                // The Members screen is where "joined with your invite" and Remove live.
                if (result == SnackbarResult.ActionPerformed) onOpenMembers()
            }
        }
    }
    // SPEC §4.2: a reversible destructive action gets an Undo. The expense
    // editor reports what it just moved to trash; this screen offers it back.
    LaunchedEffect(deletedExpenseId) {
        if (deletedExpenseId != null) {
            onDeletedShown()
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.expense_deleted),
                actionLabel = context.getString(R.string.expense_deleted_undo),
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreExpense(app.dibs.core.domain.model.ExpenseId(deletedExpenseId))
            }
        }
    }
    val haptics = LocalHapticFeedback.current
    GroupDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenMembers = onOpenMembers,
        onAddExpense = onAddExpense,
        onOpenExpense = onOpenExpense,
        onDuplicateExpense = onDuplicateExpense,
        onEditGroup = viewModel::editGroup,
        onRecordSettlement = { from, to, amount, currency, kind ->
            // SPEC §4.2: a confirming tick when a payment is recorded.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.recordSettlement(from, to, amount, currency, kind)
        },
        onOpenTrash = onOpenTrash,
        onSetArchived = viewModel::setArchived,
        onScanReceipt = onScanReceipt,
        onExportCsv = {
            val name = uiState.state?.meta?.name?.ifBlank { null } ?: "dibs-group"
            exportLauncher.launch("$name.csv")
        },
        onShareSync = {
            scope.launch {
                val bytes = viewModel.exportSyncBundle()
                val uri = withContext(Dispatchers.IO) {
                    SyncSharing.stage(context, bytes, uiState.state?.meta?.name)
                }
                context.startActivity(SyncSharing.shareIntent(context, uri))
            }
        },
        onImportSync = { importSyncLauncher.launch(arrayOf("*/*")) },
        onRemind = { uiState.state?.let { context.startActivity(BalanceSummary.shareIntent(context, it)) } },
        snackbarHostState = snackbarHostState,
    )
}

/** SPEC §4.1 screen 2: header with net position and sync chip; Expenses / Balances / Settle up tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    uiState: GroupDetailUiState,
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onAddExpense: () -> Unit,
    onOpenExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onDuplicateExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onEditGroup: (name: String?, emoji: String?, currency: CurrencyCode?) -> Unit,
    onRecordSettlement: (MemberId, MemberId, app.dibs.core.domain.money.Money, CurrencyCode, String?) -> Unit,
    onOpenTrash: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onExportCsv: () -> Unit = {},
    onScanReceipt: () -> Unit = {},
    onShareSync: () -> Unit = {},
    onImportSync: () -> Unit = {},
    onRemind: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state = uiState.state ?: return
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(listOfNotNull(state.meta?.emoji, state.meta?.name).joinToString(" ")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.detail_edit_action))
                    }
                    IconButton(onClick = onOpenMembers) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.detail_members_action))
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.detail_more_actions),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.trash_action)) },
                            onClick = {
                                menuOpen = false
                                onOpenTrash()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.scan_receipt_action)) },
                            onClick = {
                                menuOpen = false
                                onScanReceipt()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.remind_action)) },
                            onClick = {
                                menuOpen = false
                                onRemind()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_csv_action)) },
                            onClick = {
                                menuOpen = false
                                onExportCsv()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_share_action)) },
                            onClick = {
                                menuOpen = false
                                onShareSync()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_import_action)) },
                            onClick = {
                                menuOpen = false
                                onImportSync()
                            },
                        )
                        val archived = state.archived == true
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (archived) R.string.unarchive_action else R.string.archive_action,
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSetArchived(!archived)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddExpense) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.detail_add_expense))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DetailHeader(uiState, onOpenMembers)
            TabRow(selectedTabIndex = selectedTab) {
                listOf(
                    R.string.detail_tab_expenses,
                    R.string.detail_tab_balances,
                    R.string.detail_tab_settle,
                ).forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
            when (selectedTab) {
                0 -> ExpensesTab(state, onOpenExpense, onDuplicateExpense)
                1 -> BalancesTabContent(state, uiState.selfMember) { from, to, amount, currency ->
                    onRecordSettlement(from, to, amount, currency, "writeoff")
                }
                else -> SettleTabContent(state, uiState.selfMember) { from, to, amount, currency ->
                    onRecordSettlement(from, to, amount, currency, null)
                }
            }
        }
    }

    if (showEditDialog) {
        EditGroupDialog(
            currentName = state.meta?.name.orEmpty(),
            currentEmoji = state.meta?.emoji,
            onSave = { name, emoji ->
                onEditGroup(name, emoji, null)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }
}

@Composable
private fun ExpensesTab(
    state: GroupState,
    onOpenExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onDuplicateExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
) {
    val active = state.activeExpenses.sortedWith(
        compareByDescending<app.dibs.core.domain.ledger.ExpenseState> { it.fields.dateEpochDay }
            .thenByDescending { it.id },
    )
    if (active.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.detail_expenses_empty_title),
            body = stringResource(R.string.detail_expenses_empty_body),
        )
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var memberFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = ExpenseFilter.apply(active, query, memberFilter?.let(::MemberId), categoryFilter)
    val categories = active.mapNotNull { it.fields.category }.distinct().sorted()

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spacingMd)
                .testTag("expense-search"),
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingXs),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.spacingXs),
        ) {
            androidx.compose.material3.FilterChip(
                selected = memberFilter == null,
                onClick = { memberFilter = null },
                label = { Text(stringResource(R.string.filter_all_members)) },
            )
            state.members.values.filter { it.active && !it.removed }.forEach { member ->
                androidx.compose.material3.FilterChip(
                    selected = memberFilter == member.id.value,
                    onClick = {
                        memberFilter = if (memberFilter == member.id.value) null else member.id.value
                    },
                    label = { Text(member.name) },
                )
            }
            categories.forEach { category ->
                androidx.compose.material3.FilterChip(
                    selected = categoryFilter == category,
                    onClick = { categoryFilter = if (categoryFilter == category) null else category },
                    label = { Text(category) },
                )
            }
        }
        if (filtered.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.search_no_results_title),
                body = stringResource(R.string.search_no_results_body),
            )
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
                items(filtered, key = { it.id.value }) { expense ->
                    ExpenseRow(state, expense, onOpenExpense, onDuplicateExpense)
                }
            }
        }
    }
}

@Composable
private fun ExpenseRow(
    state: GroupState,
    expense: app.dibs.core.domain.ledger.ExpenseState,
    onOpenExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onDuplicateExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val payerName = expense.fields.paidBy.keys.firstOrNull()
        ?.let { state.members[it]?.name }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenExpense(expense.id) }
            .padding(vertical = Dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(expense.fields.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.expenses_paid_by, payerName) + " · " +
                    java.time.LocalDate.ofEpochDay(expense.fields.dateEpochDay).format(
                        java.time.format.DateTimeFormatter.ofLocalizedDate(
                            java.time.format.FormatStyle.MEDIUM,
                        ),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            MoneyFormatter.format(expense.fields.total, expense.fields.currency),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.expenses_row_actions, expense.fields.description),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.expenses_duplicate)) },
                onClick = {
                    menuOpen = false
                    onDuplicateExpense(expense.id)
                },
            )
        }
    }
}

@Composable
private fun DetailHeader(uiState: GroupDetailUiState, onOpenMembers: () -> Unit) {
    val net = uiState.myNet
    val currency = uiState.state?.meta?.defaultCurrency
    val moneyColors = LocalMoneyColors.current
    // Column, not a single Row: at 200% font the net line and the chips need
    // to stack instead of clipping (NFR-10).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.spacingMd),
    ) {
        if (uiState.notAMember) {
            Text(
                stringResource(R.string.detail_not_a_member),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(bottom = Dimens.spacingSm)
                    .testTag("not-a-member"),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            if (net != null && currency != null) {
                when {
                    net.isPositive -> Text(
                        stringResource(R.string.groups_owed, MoneyFormatter.format(net, currency)),
                        color = moneyColors.positive,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    net.isNegative -> Text(
                        stringResource(R.string.groups_owe, MoneyFormatter.format(-net, currency)),
                        color = moneyColors.negative,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    else -> Text(
                        stringResource(R.string.groups_settled),
                        color = moneyColors.neutral,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // T3: members were reachable only through an unlabeled toolbar
            // icon. A labeled chip makes the destination visible; with only
            // yourself in the group it becomes the "Add members" call to action.
            val memberCount = uiState.state?.members?.values?.count { !it.removed } ?: 0
            AssistChip(
                onClick = onOpenMembers,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.chipIconSize),
                    )
                },
                label = {
                    Text(
                        if (memberCount <= 1) {
                            stringResource(R.string.detail_add_members)
                        } else {
                            stringResource(R.string.detail_members_chip, memberCount)
                        },
                    )
                },
                modifier = Modifier.testTag("members-chip"),
            )
            if (uiState.pendingCount > 0) {
                Spacer(Modifier.width(Dimens.spacingSm))
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.detail_syncing)) })
            }
            // FR-Y4: the sync chip is always honest and never a silent failure.
            // NeverShared stays quiet — sync is opt-in, and nagging an offline-
            // only group would violate "nothing about sync blocks entry".
            when (val sync = uiState.syncStatus) {
                is SyncStatus.ChangesToShare -> {
                    Spacer(Modifier.width(Dimens.spacingSm))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                pluralStringResource(
                                    R.plurals.sync_chip_changes,
                                    sync.count.toInt(),
                                    sync.count.toInt(),
                                ),
                            )
                        },
                        modifier = Modifier.testTag("sync-chip"),
                    )
                }
                is SyncStatus.UpToDate -> {
                    Spacer(Modifier.width(Dimens.spacingSm))
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.sync_chip_up_to_date)) },
                        modifier = Modifier.testTag("sync-chip"),
                    )
                }
                SyncStatus.NeverShared, null -> Unit
            }
        }
    }
}

@Composable
private fun EditGroupDialog(
    currentName: String,
    currentEmoji: String?,
    onSave: (name: String, emoji: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    var emoji by rememberSaveable { mutableStateOf(currentEmoji.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_group_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.create_group_name_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(Dimens.spacingSm))
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text(stringResource(R.string.create_group_emoji_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, emoji.ifBlank { null }) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.edit_group_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_group_cancel)) }
        },
    )
}
