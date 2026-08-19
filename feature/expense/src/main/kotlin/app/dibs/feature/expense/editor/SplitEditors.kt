// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // one composable per split mode, by design

package app.dibs.feature.expense.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.ui.components.MemberAvatar
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.expense.R
import java.util.UUID

/** The horizontally scrollable split-mode chip row (SPEC §4.1 screen 3 — never a dropdown). */
@Composable
internal fun SplitModeChips(selected: SplitModeUi, onSelect: (SplitModeUi) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
    ) {
        SplitModeUi.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(stringResource(mode.labelRes())) },
                modifier = Modifier.testTag("mode-${mode.tag}"),
            )
        }
    }
}

internal fun SplitModeUi.labelRes(): Int = when (this) {
    SplitModeUi.EQUAL -> R.string.mode_equal
    SplitModeUi.EXACT -> R.string.mode_exact
    SplitModeUi.PERCENT -> R.string.mode_percent
    SplitModeUi.SHARES -> R.string.mode_shares
    SplitModeUi.ADJUSTMENT -> R.string.mode_adjustment
    SplitModeUi.ITEMIZED -> R.string.mode_itemized
}

/** Per-participant rows for every non-itemized mode; each mode adds its own trailing input. */
@Composable
internal fun ParticipantsEditor(
    members: List<EditorMember>,
    draft: EditorDraft,
    mode: SplitModeUi,
    onDraftChange: ((EditorDraft) -> EditorDraft) -> Unit,
) {
    Column {
        Text(stringResource(R.string.editor_participants), style = MaterialTheme.typography.labelLarge)
        members.forEach { member ->
            val selected = member.id.value in draft.participants
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked ->
                        onDraftChange {
                            it.copy(
                                participants = if (checked) {
                                    it.participants + member.id.value
                                } else {
                                    it.participants - member.id.value
                                },
                            )
                        }
                    },
                    modifier = Modifier.testTag("participant-${member.id.value}"),
                )
                MemberAvatar(name = member.name, memberId = member.id, size = Dimens.spacingXl)
                Spacer(Modifier.width(Dimens.spacingSm))
                Text(member.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selected) {
                    ModeInput(member, draft, mode, onDraftChange)
                }
            }
        }
    }
}

@Composable
private fun ModeInput(
    member: EditorMember,
    draft: EditorDraft,
    mode: SplitModeUi,
    onDraftChange: ((EditorDraft) -> EditorDraft) -> Unit,
) {
    val id = member.id.value
    when (mode) {
        SplitModeUi.EQUAL, SplitModeUi.ITEMIZED -> Unit
        SplitModeUi.EXACT -> SmallAmountField(
            value = draft.exactText[id].orEmpty(),
            tag = "exact-$id",
            onChange = { text -> onDraftChange { it.copy(exactText = it.exactText + (id to text)) } },
        )
        SplitModeUi.PERCENT -> SmallAmountField(
            value = draft.percentText[id].orEmpty(),
            tag = "percent-$id",
            suffix = "%",
            onChange = { text -> onDraftChange { it.copy(percentText = it.percentText + (id to text)) } },
        )
        SplitModeUi.ADJUSTMENT -> SmallAmountField(
            value = draft.adjustmentText[id].orEmpty(),
            tag = "adjust-$id",
            signed = true,
            onChange = { text -> onDraftChange { it.copy(adjustmentText = it.adjustmentText + (id to text)) } },
        )
        SplitModeUi.SHARES -> SharesStepper(
            count = draft.shares[id] ?: 0L,
            onChange = { count -> onDraftChange { it.copy(shares = it.shares + (id to count)) } },
        )
    }
}

@Composable
private fun SmallAmountField(
    value: String,
    tag: String,
    onChange: (String) -> Unit,
    suffix: String? = null,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal,
        ),
        modifier = Modifier
            .width(120.dp)
            .testTag(tag),
    )
}

@Composable
private fun SharesStepper(count: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (count > 0) onChange(count - 1) }) {
            Text("\u2212", style = MaterialTheme.typography.titleLarge)
        }
        Text("$count", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(count + 1) }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Item cards with consumer chips, tax and tip — the itemized editor (SPEC §5.3). */
@Composable
internal fun ItemizedEditor(
    members: List<EditorMember>,
    draft: EditorDraft,
    currency: CurrencyCode,
    onDraftChange: ((EditorDraft) -> EditorDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        draft.items.forEachIndexed { index, item ->
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Dimens.spacingSm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = item.name,
                            onValueChange = { text ->
                                onDraftChange { it.updateItem(index) { i -> i.copy(name = text) } }
                            },
                            label = { Text(stringResource(R.string.itemized_item_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Dimens.spacingSm))
                        OutlinedTextField(
                            value = item.priceText,
                            onValueChange = { text ->
                                onDraftChange { it.updateItem(index) { i -> i.copy(priceText = text) } }
                            },
                            label = { Text(stringResource(R.string.itemized_item_price)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .width(120.dp)
                                .testTag("item-price-$index"),
                        )
                        IconButton(onClick = {
                            onDraftChange { it.copy(items = it.items.filterIndexed { i, _ -> i != index }) }
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.itemized_remove_item))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                    ) {
                        members.forEach { member ->
                            val consuming = item.consumers.containsKey(member.id.value)
                            FilterChip(
                                selected = consuming,
                                onClick = {
                                    onDraftChange {
                                        it.updateItem(index) { i ->
                                            i.copy(
                                                consumers = if (consuming) {
                                                    i.consumers - member.id.value
                                                } else {
                                                    i.consumers + (member.id.value to 1L)
                                                },
                                            )
                                        }
                                    }
                                },
                                label = { Text(member.name) },
                            )
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                onDraftChange { it.copy(items = it.items + ItemDraft(id = UUID.randomUUID().toString())) }
            },
            modifier = Modifier.testTag("add-item"),
        ) {
            Text(stringResource(R.string.itemized_add_item))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
            OutlinedTextField(
                value = draft.taxText,
                onValueChange = { text -> onDraftChange { it.copy(taxText = text) } },
                label = { Text(stringResource(R.string.itemized_tax)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.tipText,
                onValueChange = { text -> onDraftChange { it.copy(tipText = text) } },
                label = { Text(stringResource(R.string.itemized_tip)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        itemizedTotalOf(draft, currency)?.let { total ->
            Text(
                stringResource(R.string.editor_amount_computed) + ": " + MoneyFormatter.format(total, currency),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun EditorDraft.updateItem(index: Int, transform: (ItemDraft) -> ItemDraft): EditorDraft =
    copy(items = items.mapIndexed { i, item -> if (i == index) transform(item) else item })

/**
 * The live "remaining to assign" bar — green at exactly zero (SPEC §4.1
 * screen 4) — plus the who-absorbed-the-cent note (SPEC §5.2).
 */
@Composable
internal fun RemainingBar(preview: SplitPreview, currency: CurrencyCode, members: List<EditorMember>) {
    val moneyColors = LocalMoneyColors.current
    val (text, color) = when (preview) {
        is SplitPreview.Ready -> stringResource(R.string.remaining_ready) to moneyColors.positive
        is SplitPreview.RemainingMoney ->
            if (preview.remaining.isNegative) {
                stringResource(R.string.remaining_over, MoneyFormatter.format(-preview.remaining, currency)) to
                    moneyColors.negative
            } else {
                stringResource(R.string.remaining_left, MoneyFormatter.format(preview.remaining, currency)) to
                    MaterialTheme.colorScheme.tertiary
            }
        is SplitPreview.RemainingPercent -> {
            val bp = preview.shortfallBasisPoints
            val display = formatBasisPoints(if (bp < 0) -bp else bp)
            if (bp < 0) {
                stringResource(R.string.remaining_percent_over, display) to moneyColors.negative
            } else {
                stringResource(R.string.remaining_percent_left, display) to MaterialTheme.colorScheme.tertiary
            }
        }
        is SplitPreview.NotReady -> stringResource(preview.issue.messageRes()) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.testTag("remaining-bar")) {
        Text(text, color = color, style = MaterialTheme.typography.titleSmall)
        if (preview is SplitPreview.Ready && preview.absorbers.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.spacingXs))
            preview.absorbers.forEach { absorber ->
                val name = members.firstOrNull { it.id == absorber.memberId }?.name ?: "?"
                Text(
                    stringResource(
                        R.string.remaining_rounding_note,
                        name,
                        MoneyFormatter.format(
                            app.dibs.core.domain.money.Money(absorber.roundingAdjustment),
                            currency,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatBasisPoints(bp: Long): String {
    val whole = bp / 100
    val fraction = (bp % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
}

internal fun SplitPreview.Issue.messageRes(): Int = when (this) {
    SplitPreview.Issue.NO_AMOUNT -> R.string.issue_no_amount
    SplitPreview.Issue.NO_PARTICIPANTS -> R.string.issue_no_participants
    SplitPreview.Issue.ALL_SHARES_ZERO -> R.string.issue_all_shares_zero
    SplitPreview.Issue.ADJUSTMENTS_EXCEED -> R.string.issue_adjustments_exceed
    SplitPreview.Issue.NEGATIVE_SHARE -> R.string.issue_negative_share
    SplitPreview.Issue.ITEM_UNASSIGNED -> R.string.issue_item_unassigned
    SplitPreview.Issue.INVALID_INPUT -> R.string.issue_invalid_input
    SplitPreview.Issue.OVERFLOW -> R.string.issue_overflow
}
