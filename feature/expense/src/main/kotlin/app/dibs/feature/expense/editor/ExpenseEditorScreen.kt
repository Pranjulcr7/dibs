// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.expense.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.ledger.ExpenseEvent
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.AmountTextStyles
import app.dibs.core.ui.theme.Dimens
import app.dibs.core.ui.theme.LocalMoneyColors
import app.dibs.feature.expense.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Machine tags stored in ops; localized labels at display (D30-adjacent: tags never localize). */
private val Categories = listOf(
    "general" to R.string.category_general,
    "food" to R.string.category_food,
    "groceries" to R.string.category_groceries,
    "transport" to R.string.category_transport,
    "housing" to R.string.category_housing,
    "utilities" to R.string.category_utilities,
    "entertainment" to R.string.category_entertainment,
    "travel" to R.string.category_travel,
)

@Composable
fun ExpenseEditorRoute(onDone: () -> Unit, viewModel: ExpenseEditorViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onDone() }
    }
    ExpenseEditorScreen(
        uiState = uiState,
        onDraftChange = viewModel::updateDraft,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onBack = onDone,
    )
}

/** SPEC §4.1 screen 3: amount first and focused, chips for split mode, live remaining bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // one form section per branch; the screen IS the form
@Composable
fun ExpenseEditorScreen(
    uiState: EditorUiState,
    onDraftChange: ((EditorDraft) -> EditorDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = uiState.draft
    val currency = uiState.currency ?: return
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    // NFR-2: the fast path is type-amount-and-go, so the keyboard must already
    // be up on a fresh expense — but never steal focus from an edit or a
    // receipt hand-off, and never re-steal it after rotation.
    val amountFocus = remember { FocusRequester() }
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    val wantsAmountFocus = uiState.loaded && !uiState.isEdit &&
        uiState.mode != SplitModeUi.ITEMIZED && draft.amountText.isEmpty()
    LaunchedEffect(wantsAmountFocus) {
        if (wantsAmountFocus && !autoFocused) {
            // One frame so the field's focus node is attached before the request.
            withFrameNanos { }
            amountFocus.requestFocus()
            autoFocused = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (uiState.isEdit) R.string.editor_title_edit else R.string.editor_title_new))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
                actions = {
                    if (uiState.isEdit) {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.editor_history),
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.editor_delete))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(Dimens.spacingMd)) {
                RemainingBar(uiState.preview, currency, uiState.members)
                // The split being ready is not the whole story — say so when the
                // one thing missing is a description, instead of a mute button.
                if (uiState.blockedOnDescription) {
                    Spacer(Modifier.height(Dimens.spacingXs))
                    Text(
                        stringResource(R.string.editor_needs_description),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("needs-description"),
                    )
                }
                Spacer(Modifier.height(Dimens.spacingSm))
                Button(
                    onClick = onSave,
                    enabled = uiState.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save-expense"),
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.spacingMd)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        ) {
            if (uiState.mode == SplitModeUi.ITEMIZED) {
                Text(
                    text = uiState.itemizedTotal?.let {
                        app.dibs.core.ui.money.MoneyFormatter.format(it, currency)
                    } ?: stringResource(R.string.editor_amount_computed),
                    style = AmountTextStyles.large,
                    modifier = Modifier.testTag("amount-computed"),
                )
            } else {
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { text -> onDraftChange { it.copy(amountText = text) } },
                    label = { Text(stringResource(R.string.editor_amount_label) + " (" + currency.value + ")") },
                    textStyle = AmountTextStyles.medium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(amountFocus)
                        .testTag("amount"),
                )
            }

            OutlinedTextField(
                value = draft.description,
                onValueChange = { text -> onDraftChange { it.copy(description = text) } },
                label = { Text(stringResource(R.string.editor_description_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("description"),
            )

            // Category chips
            Column {
                Text(stringResource(R.string.editor_category), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Dimens.spacingXs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                ) {
                    Categories.forEach { (tag, label) ->
                        FilterChip(
                            selected = draft.category == tag,
                            onClick = {
                                onDraftChange { it.copy(category = if (it.category == tag) null else tag) }
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }

            // Date
            val date = LocalDate.ofEpochDay(draft.dateEpochDay)
            TextButton(onClick = { showDatePicker = true }, modifier = Modifier.testTag("date")) {
                Text(
                    stringResource(R.string.editor_date_label) + ": " +
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                )
            }

            // Payer
            Column {
                Text(stringResource(R.string.editor_paid_by), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Dimens.spacingXs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                ) {
                    uiState.members.forEach { member ->
                        FilterChip(
                            selected = !draft.multiPayer && draft.payer == member.id.value,
                            onClick = { onDraftChange { it.copy(payer = member.id.value, multiPayer = false) } },
                            label = { Text(member.name) },
                            modifier = Modifier.testTag("payer-${member.id.value}"),
                        )
                    }
                    // FR-E3: three people chipped in for the Airbnb.
                    FilterChip(
                        selected = draft.multiPayer,
                        onClick = {
                            onDraftChange { current ->
                                // Start from what is already known: the single payer paid it all.
                                val seeded = current.paidText.ifEmpty {
                                    current.payer?.let { mapOf(it to current.amountText) }.orEmpty()
                                }
                                current.copy(multiPayer = !current.multiPayer, paidText = seeded)
                            }
                        },
                        label = { Text(stringResource(R.string.editor_several_payers)) },
                        modifier = Modifier.testTag("payer-several"),
                    )
                }
                if (draft.multiPayer) {
                    MultiPayerEditor(uiState, currency, onDraftChange)
                }
            }

            // Split mode + per-mode editor
            Column {
                Text(stringResource(R.string.editor_split_mode), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Dimens.spacingXs))
                SplitModeChips(uiState.mode) { mode -> onDraftChange { it.copy(mode = mode.tag) } }
            }

            if (uiState.mode == SplitModeUi.ITEMIZED) {
                ItemizedEditor(uiState.members, draft, currency, onDraftChange)
            } else {
                ParticipantsEditor(uiState.members, draft, uiState.mode, onDraftChange)
            }

            if (!uiState.isEdit) {
                Column {
                    Text(stringResource(R.string.editor_repeat), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(Dimens.spacingXs))
                    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                        listOf(
                            null to R.string.editor_repeat_none,
                            REPEAT_WEEKLY to R.string.editor_repeat_weekly,
                            REPEAT_MONTHLY to R.string.editor_repeat_monthly,
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = draft.repeatDays == days,
                                onClick = {
                                    onDraftChange { it.copy(repeatDays = days) }
                                    if (days != null && android.os.Build.VERSION.SDK_INT >= NOTIF_PERMISSION_SDK) {
                                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = draft.note,
                onValueChange = { text -> onDraftChange { it.copy(note = text) } },
                label = { Text(stringResource(R.string.editor_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.spacingXl))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.dateEpochDay * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onDraftChange { it.copy(dateEpochDay = millis / MILLIS_PER_DAY) }
                    }
                    showDatePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.padding(Dimens.spacingMd)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Dimens.spacingSm))
                uiState.history.forEach { event ->
                    Text(
                        text = stringResource(event.kind.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(Dimens.spacingLg))
            }
        }
    }
}

private fun ExpenseEvent.Kind.labelRes(): Int = when (this) {
    ExpenseEvent.Kind.CREATED -> R.string.history_created
    ExpenseEvent.Kind.EDITED -> R.string.history_edited
    ExpenseEvent.Kind.DELETED -> R.string.history_deleted
    ExpenseEvent.Kind.RESTORED -> R.string.history_restored
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val REPEAT_WEEKLY = 7
private const val REPEAT_MONTHLY = 30
private const val NOTIF_PERMISSION_SDK = 33

/** One amount per member, with the same "left to assign" language as the split editors. */
@Composable
private fun MultiPayerEditor(
    uiState: EditorUiState,
    currency: CurrencyCode,
    onDraftChange: ((EditorDraft) -> EditorDraft) -> Unit,
) {
    val moneyColors = LocalMoneyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        uiState.members.forEach { member ->
            OutlinedTextField(
                value = uiState.draft.paidText[member.id.value].orEmpty(),
                onValueChange = { text ->
                    onDraftChange { it.copy(paidText = it.paidText + (member.id.value to text)) }
                },
                label = { Text(stringResource(R.string.editor_paid_amount, member.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paid-${member.id.value}"),
            )
        }
        val (text, color) = when (val payers = uiState.payers) {
            is PayerPreview.Ready -> stringResource(R.string.payers_ready) to moneyColors.positive
            is PayerPreview.Remaining ->
                if (payers.remaining.isNegative) {
                    stringResource(R.string.payers_over, MoneyFormatter.format(-payers.remaining, currency)) to
                        moneyColors.negative
                } else {
                    stringResource(R.string.payers_left, MoneyFormatter.format(payers.remaining, currency)) to
                        moneyColors.neutral
                }
            PayerPreview.NoPayer -> stringResource(R.string.payers_none) to moneyColors.neutral
            PayerPreview.Invalid -> stringResource(R.string.issue_invalid_input) to moneyColors.negative
            PayerPreview.NoAmount -> stringResource(R.string.issue_no_amount) to moneyColors.neutral
        }
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("payers-status"),
        )
    }
}
