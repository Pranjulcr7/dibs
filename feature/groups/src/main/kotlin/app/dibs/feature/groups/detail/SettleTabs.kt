// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.dibs.core.domain.ledger.DuplicateSettlements
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.MemberStatement
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.money.MoneyInput
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.groups.R

internal fun GroupState.memberName(id: MemberId): String = members[id]?.name ?: "?"

/** Balances tab: net list, then raw who-owes-whom with the opt-in simplify toggle (FR-S1/S2). */
@Composable
internal fun BalancesTabContent(
    state: GroupState,
    selfMember: MemberId?,
    onWriteOff: (from: MemberId, to: MemberId, amount: Money, currency: CurrencyCode) -> Unit,
) {
    var simplify by rememberSaveable { mutableStateOf(false) }
    // FR-S5: tapping a member's net opens what it is made of. Saved as the raw
    // id so the open statement survives rotation.
    var statementFor by rememberSaveable { mutableStateOf<String?>(null) }
    statementFor?.let { memberId ->
        MemberStatementContent(state, MemberId(memberId), selfMember) { statementFor = null }
        return
    }
    val balances = state.balances()
    if (balances.isEmpty() || balances.values.all { list -> list.all { it.net.isZero } }) {
        EmptyState(
            title = stringResource(R.string.detail_balances_empty_title),
            body = stringResource(R.string.detail_balances_empty_body),
        )
        return
    }
    val moneyColors = LocalMoneyColors.current
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
        item(key = "nets-header") {
            Text(stringResource(R.string.balances_members_header), style = MaterialTheme.typography.labelLarge)
        }
        balances.forEach { (currency, memberBalances) ->
            val sorted = memberBalances.sortedByDescending { it.memberId == selfMember }
            items2(sorted, key = { "net-${currency.value}-${it.memberId.value}" }) { balance ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.minTouchTarget)
                        .clickable(
                            onClickLabel = stringResource(R.string.statement_open),
                            role = Role.Button,
                        ) { statementFor = balance.memberId.value }
                        .testTag("net-${balance.memberId.value}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (balance.memberId == selfMember) {
                            stringResource(R.string.detail_you)
                        } else {
                            state.memberName(balance.memberId)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        MoneyFormatter.format(balance.net, currency),
                        color = when {
                            balance.net.isPositive -> moneyColors.positive
                            balance.net.isNegative -> moneyColors.negative
                            else -> moneyColors.neutral
                        },
                    )
                }
            }
        }
        item(key = "pairs-header") {
            Column {
                Spacer(Modifier.height(Dimens.spacingMd))
                HorizontalDivider()
                Spacer(Modifier.height(Dimens.spacingMd))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.balances_pairs_header),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // The words and the switch are one control (NFR-10): tapping
                    // "Simplify debts" toggles it, and a screen reader announces
                    // "Simplify debts, switch, off" instead of a nameless switch.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = Dimens.minTouchTarget)
                            .toggleable(value = simplify, role = Role.Switch, onValueChange = { simplify = it })
                            .testTag("simplify-toggle"),
                    ) {
                        Text(stringResource(R.string.balances_simplify), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(Dimens.spacingXs))
                        Switch(checked = simplify, onCheckedChange = null)
                    }
                }
                if (simplify) {
                    Text(
                        stringResource(R.string.balances_simplify_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val pairsByCurrency = if (simplify) {
            state.suggestedTransfers().mapValues { (_, transfers) ->
                transfers.map { Triple(it.from, it.to, it.amount) }
            }
        } else {
            state.pairwiseDebts().mapValues { (_, debts) ->
                debts.map { Triple(it.from, it.to, it.amount) }
            }
        }
        pairsByCurrency.forEach { (currency, pairs) ->
            items2(
                pairs,
                key = { "pair-${currency.value}-${it.first.value}-${it.second.value}" },
            ) { (from, to, amount) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.balances_pair_owes,
                            state.memberName(from),
                            state.memberName(to),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(MoneyFormatter.format(amount, currency), color = moneyColors.negative)
                    Spacer(Modifier.width(Dimens.spacingSm))
                    TextButton(onClick = { onWriteOff(from, to, amount, currency) }) {
                        Text(stringResource(R.string.balances_write_off))
                    }
                }
            }
        }
        item(key = "writeoff-note") {
            Text(
                stringResource(R.string.balances_write_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Settle-up tab (SPEC §4.1 screen 8): suggestions, manual payments, history. Hidden solo (edge 16). */
@Composable
internal fun SettleTabContent(
    state: GroupState,
    selfMember: MemberId?,
    onRecord: (from: MemberId, to: MemberId, amount: Money, currency: CurrencyCode) -> Unit,
) {
    val activeMembers = state.members.values.filter { it.active && !it.removed }
    if (activeMembers.size < 2) {
        EmptyState(
            title = stringResource(R.string.settle_only_you_title),
            body = stringResource(R.string.settle_only_you_body),
        )
        return
    }
    var dialogPrefill by remember { mutableStateOf<Triple<MemberId, MemberId, Pair<Money, CurrencyCode>>?>(null) }
    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    val transfers = state.suggestedTransfers().entries.flatMap { (currency, list) ->
        list.map { it to currency }
    }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
        if (transfers.isEmpty()) {
            item(key = "settled") {
                Column {
                    Text(
                        stringResource(R.string.settle_all_settled_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.settle_all_settled_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item(key = "suggested-header") {
                Text(stringResource(R.string.settle_suggested_header), style = MaterialTheme.typography.labelLarge)
            }
            items2(
                transfers,
                key = { "t-${it.second.value}-${it.first.from.value}-${it.first.to.value}" },
            ) { (transfer, currency) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.spacingXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.balances_pair_owes,
                            state.memberName(transfer.from),
                            state.memberName(transfer.to),
                        ) + " · " + MoneyFormatter.format(transfer.amount, currency),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { dialogPrefill = Triple(transfer.from, transfer.to, transfer.amount to currency) },
                        modifier = Modifier.testTag("record-${transfer.from.value}"),
                    ) {
                        Text(stringResource(R.string.settle_record))
                    }
                }
            }
        }
        item(key = "manual") {
            OutlinedButton(
                onClick = { showManualDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("record-manual"),
            ) {
                Text(stringResource(R.string.settle_record_payment))
            }
        }
        if (state.settlements.isNotEmpty()) {
            val suspects = DuplicateSettlements.suspects(state)
            if (suspects.isNotEmpty()) {
                // Edge 32: both payments are kept; the screen explains the credit they caused.
                item(key = "duplicate-hint") {
                    Text(
                        stringResource(R.string.settle_duplicate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = Dimens.spacingMd)
                            .testTag("duplicate-settlement-hint"),
                    )
                }
            }
            item(key = "history-header") {
                Column {
                    Spacer(Modifier.height(Dimens.spacingMd))
                    Text(stringResource(R.string.settle_history_header), style = MaterialTheme.typography.labelLarge)
                }
            }
            items2(
                state.settlements.values.toList().asReversed(),
                key = { "s-${it.id.value}" },
            ) { settlement ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.spacingXs),
                ) {
                    Text(
                        stringResource(
                            if (settlement.kind == "writeoff") {
                                R.string.settle_history_writeoff
                            } else {
                                R.string.settle_history_paid
                            },
                            state.memberName(settlement.from),
                            state.memberName(settlement.to),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        MoneyFormatter.format(settlement.amount, settlement.currency),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    dialogPrefill?.let { (from, to, amountCurrency) ->
        RecordPaymentDialog(
            state = state,
            initialFrom = from,
            initialTo = to,
            initialAmountText = MoneyInput.toPlainString(amountCurrency.first, amountCurrency.second),
            currency = amountCurrency.second,
            onRecord = { f, t, amount ->
                onRecord(f, t, amount, amountCurrency.second)
                dialogPrefill = null
            },
            onDismiss = { dialogPrefill = null },
        )
    }
    if (showManualDialog) {
        val currency = state.meta?.defaultCurrency ?: CurrencyCode("USD")
        RecordPaymentDialog(
            state = state,
            initialFrom = null,
            initialTo = selfMember,
            initialAmountText = "",
            currency = currency,
            onRecord = { f, t, amount ->
                onRecord(f, t, amount, currency)
                showManualDialog = false
            },
            onDismiss = { showManualDialog = false },
        )
    }
}

@Composable
internal fun RecordPaymentDialog(
    state: GroupState,
    initialFrom: MemberId?,
    initialTo: MemberId?,
    initialAmountText: String,
    currency: CurrencyCode,
    onRecord: (from: MemberId, to: MemberId, amount: Money) -> Unit,
    onDismiss: () -> Unit,
) {
    val members = state.members.values.filter { it.active && !it.removed }
    var from by remember { mutableStateOf(initialFrom) }
    var to by remember { mutableStateOf(initialTo) }
    var amountText by rememberSaveable { mutableStateOf(initialAmountText) }
    val amount = MoneyInput.parse(amountText, currency)
    val valid = from != null && to != null && from != to && amount?.isPositive == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settle_payment_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settle_from), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.spacingXs),
                ) {
                    members.forEach { member ->
                        FilterChip(
                            selected = from == member.id,
                            onClick = { from = member.id },
                            label = { Text(member.name) },
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.spacingSm))
                Text(stringResource(R.string.settle_to), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.spacingXs),
                ) {
                    members.forEach { member ->
                        FilterChip(
                            selected = to == member.id,
                            onClick = { to = member.id },
                            label = { Text(member.name) },
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.spacingSm))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.settle_amount) + " (" + currency.value + ")") },
                    singleLine = true,
                    modifier = Modifier.testTag("settle-amount"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRecord(checkNotNull(from), checkNotNull(to), checkNotNull(amount)) },
                enabled = valid,
                modifier = Modifier.testTag("settle-confirm"),
            ) { Text(stringResource(R.string.settle_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settle_cancel)) }
        },
    )
}

/** LazyListScope.items with non-lazy source lists used inside forEach loops above. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    items.forEach { element ->
        item(key = key(element)) { itemContent(element) }
    }
}

/**
 * FR-S5: one member's totals and the expenses behind them. Every figure here
 * comes from [MemberStatement], whose net is property-tested equal to the
 * balance the user tapped — the drill-down cannot contradict the list.
 */
@Composable
internal fun MemberStatementContent(state: GroupState, memberId: MemberId, selfMember: MemberId?, onClose: () -> Unit) {
    val statements = MemberStatement.of(state, memberId)
    val moneyColors = LocalMoneyColors.current
    val name = if (memberId == selfMember) stringResource(R.string.detail_you) else state.memberName(memberId)
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd)) {
        item(key = "statement-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.statement_title, name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose, modifier = Modifier.testTag("statement-close")) {
                    Text(stringResource(R.string.statement_close))
                }
            }
        }
        if (statements.isEmpty()) {
            item(key = "statement-empty") { Text(stringResource(R.string.statement_empty)) }
        }
        statements.forEach { statement ->
            val currency = statement.currency
            item(key = "totals-${currency.value}") {
                Column(Modifier.padding(vertical = Dimens.spacingSm)) {
                    StatementTotal(R.string.statement_paid, statement.paid, currency)
                    StatementTotal(R.string.statement_share, statement.share, currency)
                    if (!statement.paymentsMade.isZero) {
                        StatementTotal(R.string.statement_payments_made, statement.paymentsMade, currency)
                    }
                    if (!statement.paymentsReceived.isZero) {
                        StatementTotal(R.string.statement_payments_received, statement.paymentsReceived, currency)
                    }
                    Row {
                        Text(
                            stringResource(R.string.statement_net),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            MoneyFormatter.format(statement.net, currency),
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                statement.net.isPositive -> moneyColors.positive
                                statement.net.isNegative -> moneyColors.negative
                                else -> moneyColors.neutral
                            },
                            modifier = Modifier.testTag("statement-net-${currency.value}"),
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = Dimens.spacingSm))
                }
            }
            items2(statement.lines, key = { "line-${currency.value}-${it.expenseId.value}" }) { line ->
                Column(Modifier.padding(vertical = Dimens.spacingXs)) {
                    Text(line.description, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(
                            R.string.statement_line,
                            MoneyFormatter.format(line.paid, currency),
                            MoneyFormatter.format(line.share, currency),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatementTotal(label: Int, amount: Money, currency: CurrencyCode) {
    Row {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Text(MoneyFormatter.format(amount, currency))
    }
}
