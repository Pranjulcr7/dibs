// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // list, cards, activity feed and previews share this file

package app.dibs.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.groups.list.GroupCardUi
import app.dibs.feature.groups.list.GroupsUiState
import app.dibs.feature.groups.list.GroupsViewModel

@Composable
fun GroupsRoute(
    onOpenGroup: (GroupId) -> Unit,
    onCreateGroup: () -> Unit,
    onImportFromSplitwise: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    GroupsScreen(
        uiState = uiState,
        onOpenGroup = onOpenGroup,
        onCreateGroup = onCreateGroup,
        onImportFromSplitwise = onImportFromSplitwise,
        modifier = modifier,
    )
}

/** SPEC §4.1 screen 1: group cards with the user's color-coded net position. */
@Composable
fun GroupsScreen(
    uiState: GroupsUiState,
    onOpenGroup: (GroupId) -> Unit,
    onCreateGroup: () -> Unit,
    onImportFromSplitwise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        GroupsUiState.Loading -> Spacer(modifier)
        is GroupsUiState.Content ->
            if (uiState.groups.isEmpty()) {
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
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
                ) {
                    val (archived, active) = uiState.groups.partition { it.archived }
                    items(active, key = { it.id.value }) { card ->
                        GroupCard(card, onClick = { onOpenGroup(card.id) })
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archived-header") {
                            Text(
                                stringResource(R.string.groups_archived_header),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(archived, key = { it.id.value }) { card ->
                            GroupCard(card, onClick = { onOpenGroup(card.id) })
                        }
                    }
                }
            }
    }
}

@Composable
private fun GroupCard(card: GroupCardUi, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Dimens.spacingMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.emoji != null) {
                Text(card.emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(Dimens.spacingMd))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name.ifEmpty { stringResource(R.string.groups_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralMembers(card.memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NetPosition(card)
        }
    }
}

@Composable
private fun pluralMembers(count: Int): String = if (count == 1) {
    stringResource(R.string.groups_member_count_one)
} else {
    stringResource(R.string.groups_member_count, count)
}

@Composable
private fun NetPosition(card: GroupCardUi) {
    val net = card.myNet
    val currency = card.currency
    if (net == null || currency == null) return
    val moneyColors = LocalMoneyColors.current
    when {
        net.isPositive -> Text(
            stringResource(R.string.groups_owed, MoneyFormatter.format(net, currency)),
            color = moneyColors.positive,
            style = MaterialTheme.typography.labelLarge,
        )
        net.isNegative -> Text(
            stringResource(R.string.groups_owe, MoneyFormatter.format(-net, currency)),
            color = moneyColors.negative,
            style = MaterialTheme.typography.labelLarge,
        )
        else -> Text(
            stringResource(R.string.groups_settled),
            color = moneyColors.neutral,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The cross-group feed (SPEC §4.1 bottom nav: Activity). */
@Composable
fun ActivityRoute(
    onOpenExpense: (GroupId, app.dibs.core.domain.model.ExpenseId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: app.dibs.feature.groups.activity.ActivityViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    ActivityScreen(items, onOpenExpense, modifier)
}

@Composable
fun ActivityScreen(
    items: List<app.dibs.feature.groups.activity.ActivityItem>?,
    onOpenExpense: (GroupId, app.dibs.core.domain.model.ExpenseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        items == null -> Spacer(modifier)
        items.isEmpty() -> EmptyState(
            title = stringResource(R.string.activity_empty_title),
            body = stringResource(R.string.activity_empty_body),
            modifier = modifier.fillMaxSize(),
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd),
        ) {
            items(items, key = { "${it.groupId.value}-${it.expenseId.value}" }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenExpense(item.groupId, item.expenseId) }
                        .padding(vertical = Dimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.description, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.expenses_paid_by, item.payerName) + " " +
                                stringResource(R.string.activity_row_in, item.groupLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        MoneyFormatter.format(item.amount, item.currency),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

private val previewCards = GroupsUiState.Content(
    listOf(
        GroupCardUi(GroupId("g1"), "Lisbon trip", "🌊", 4, Money(4250), CurrencyCode("EUR")),
        GroupCardUi(GroupId("g2"), "Flat 4B", null, 3, Money(-1200), CurrencyCode("USD")),
        GroupCardUi(GroupId("g3"), "Poker night", "🃏", 5, Money.ZERO, CurrencyCode("USD")),
    ),
)

@Preview(name = "Light", showBackground = true)
@Composable
private fun GroupsPreviewLight() {
    DibsTheme { GroupsScreen(previewCards, {}, {}, {}) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun GroupsPreviewDark() {
    DibsTheme { GroupsScreen(previewCards, {}, {}, {}) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun GroupsPreviewLargeFont() {
    DibsTheme { GroupsScreen(previewCards, {}, {}, {}) }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun GroupsPreviewEmpty() {
    DibsTheme { GroupsScreen(GroupsUiState.Content(emptyList()), {}, {}, {}) }
}
