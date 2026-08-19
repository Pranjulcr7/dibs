// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.components.MemberAvatar
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.groups.R

@Composable
fun GroupDetailRoute(
    onBack: () -> Unit,
    onOpenMembers: () -> Unit,
    onAddExpense: () -> Unit,
    onOpenExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    onDuplicateExpense: (app.dibs.core.domain.model.ExpenseId) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    GroupDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenMembers = onOpenMembers,
        onAddExpense = onAddExpense,
        onOpenExpense = onOpenExpense,
        onDuplicateExpense = onDuplicateExpense,
        onEditGroup = viewModel::editGroup,
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
    modifier: Modifier = Modifier,
) {
    val state = uiState.state ?: return
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
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
            DetailHeader(uiState)
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
                1 -> BalancesTab(state, uiState.selfMember)
                else -> EmptyState(
                    title = stringResource(R.string.detail_settle_empty_title),
                    body = stringResource(R.string.detail_settle_empty_body),
                )
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
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
        items(active, key = { it.id.value }) { expense ->
            ExpenseRow(state, expense, onOpenExpense, onDuplicateExpense)
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
    var menuOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
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
private fun DetailHeader(uiState: GroupDetailUiState) {
    val net = uiState.myNet
    val currency = uiState.state?.meta?.defaultCurrency
    val moneyColors = LocalMoneyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
        if (uiState.pendingCount > 0) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.detail_syncing)) })
        }
    }
}

@Composable
private fun BalancesTab(state: GroupState, selfMember: MemberId?) {
    val balances = state.balances()
    if (balances.isEmpty() || balances.values.all { list -> list.all { it.net.isZero } }) {
        EmptyState(
            title = stringResource(R.string.detail_balances_empty_title),
            body = stringResource(R.string.detail_balances_empty_body),
        )
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
        balances.forEach { (currency, memberBalances) ->
            if (balances.size > 1) {
                item(key = "header-${currency.value}") {
                    Text(
                        currency.value,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = Dimens.spacingSm),
                    )
                }
            }
            // "You" pinned first (SPEC §4.1 screen 7), then by name via id order.
            val sorted = memberBalances.sortedByDescending { it.memberId == selfMember }
            items(sorted, key = { "${currency.value}-${it.memberId.value}" }) { balance ->
                BalanceRow(state, balance, currency, isSelf = balance.memberId == selfMember)
            }
        }
    }
}

@Composable
private fun BalanceRow(state: GroupState, balance: MemberBalance, currency: CurrencyCode, isSelf: Boolean) {
    val moneyColors = LocalMoneyColors.current
    val name = state.members[balance.memberId]?.name.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(name = name, memberId = balance.memberId)
        Spacer(Modifier.width(Dimens.spacingMd))
        Text(
            text = if (isSelf) stringResource(R.string.detail_you) else name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormatter.format(balance.net, currency),
            color = when {
                balance.net.isPositive -> moneyColors.positive
                balance.net.isNegative -> moneyColors.negative
                else -> moneyColors.neutral
            },
            style = MaterialTheme.typography.titleSmall,
        )
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
