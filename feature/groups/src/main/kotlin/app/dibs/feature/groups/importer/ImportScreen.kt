// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.importer.SplitwiseCsv
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R

/** FR-I1: pick → map names → preview (skips, totals, checksum) → confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportRoute(onDone: (GroupId) -> Unit, onBack: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            if (text != null) viewModel.onFileLoaded(text)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.done.collect(onDone)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.import_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.spacingMd)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        ) {
            when (val current = step) {
                is ImportStep.PickFile -> {
                    Text(stringResource(R.string.import_pick_hint))
                    Button(
                        onClick = { picker.launch(arrayOf("text/*", "application/csv")) },
                        modifier = Modifier.testTag("import-pick"),
                    ) {
                        Text(stringResource(R.string.import_pick_file))
                    }
                }
                is ImportStep.BadHeader -> {
                    Text(stringResource(R.string.import_bad_header), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv")) }) {
                        Text(stringResource(R.string.import_pick_file))
                    }
                }
                is ImportStep.Mapping -> MappingStep(
                    step = current,
                    onRename = viewModel::renameMember,
                    onChooseSelf = viewModel::chooseSelf,
                    onContinue = viewModel::toPreview,
                )
                is ImportStep.Preview -> PreviewStep(
                    step = current,
                    onBack = viewModel::backToMapping,
                    onConfirm = viewModel::confirm,
                )
                is ImportStep.Importing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.import_running))
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingStep(
    step: ImportStep.Mapping,
    onRename: (String, String) -> Unit,
    onChooseSelf: (String?) -> Unit,
    onContinue: (String) -> Unit,
) {
    var groupName by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.import_mapping_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.import_mapping_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    step.parsed.memberNames.forEach { original ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = step.names[original] ?: original,
                onValueChange = { onRename(original, it) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(Dimens.spacingXs))
            FilterChip(
                selected = step.selfName == original,
                onClick = { onChooseSelf(if (step.selfName == original) null else original) },
                label = { Text(stringResource(R.string.import_this_is_me)) },
            )
        }
    }
    OutlinedTextField(
        value = groupName,
        onValueChange = { groupName = it },
        label = { Text(stringResource(R.string.import_group_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onContinue(groupName) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("import-continue"),
    ) {
        Text(stringResource(R.string.import_continue))
    }
}

@Composable
private fun PreviewStep(step: ImportStep.Preview, onBack: () -> Unit, onConfirm: () -> Unit) {
    Text(stringResource(R.string.import_preview_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.import_preview_members, step.parsed.memberNames.size))
    val expenses = step.parsed.rows.count { it is SplitwiseCsv.Row.Expense }
    val payments = step.parsed.rows.size - expenses
    Text(stringResource(R.string.import_preview_expenses, expenses, payments))
    step.parsed.currencyTotalsMinor.forEach { (currency, total) ->
        Text("$currency: " + MoneyFormatter.format(Money(total), CurrencyCode(currency)))
    }
    if (step.parsed.skipped.isNotEmpty()) {
        Text(
            stringResource(R.string.import_preview_skipped, step.parsed.skipped.size),
            color = MaterialTheme.colorScheme.tertiary,
        )
        step.parsed.skipped.forEach { skipped ->
            Text(
                stringResource(R.string.import_preview_skipped_row, skipped.lineNumber, skipped.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!step.parsed.balanceChecksumMatches) {
        Text(stringResource(R.string.import_checksum_warning), color = MaterialTheme.colorScheme.tertiary)
    }
    if (step.alreadyImported) {
        Text(stringResource(R.string.import_already_imported), color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(Dimens.spacingSm))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.import_back))
        }
        Button(
            onClick = onConfirm,
            enabled = !step.alreadyImported,
            modifier = Modifier.testTag("import-confirm"),
        ) {
            Text(stringResource(R.string.import_confirm))
        }
    }
}

// -- previews ----------------------------------------------------------------

private val previewParsed = SplitwiseCsv.Result.Success(
    memberNames = listOf("Alice", "Bob", "Carol"),
    rows = listOf(
        SplitwiseCsv.Row.Expense(
            stableId = "p1",
            dateEpochDay = 20_500,
            description = "Dinner",
            category = "Food",
            currency = "USD",
            totalMinor = 3000,
            paidByMinor = mapOf("Alice" to 3000L),
            owedByMinor = mapOf("Alice" to 1000L, "Bob" to 1000L, "Carol" to 1000L),
        ),
        SplitwiseCsv.Row.Settlement(
            stableId = "p2",
            dateEpochDay = 20_501,
            fromName = "Bob",
            toName = "Alice",
            currency = "USD",
            amountMinor = 1000,
        ),
    ),
    skipped = listOf(SplitwiseCsv.SkippedRow(7, "…", "The amount in this row isn't a number")),
    currencyTotalsMinor = mapOf("USD" to 3000L),
    balanceChecksumMatches = true,
)

@androidx.compose.ui.tooling.preview.Preview(name = "Mapping light", showBackground = true)
@Composable
private fun MappingPreviewLight() {
    app.dibs.core.ui.theme.DibsTheme {
        androidx.compose.foundation.layout.Column {
            MappingStep(
                step = ImportStep.Mapping(previewParsed, previewParsed.memberNames.associateWith { it }, "Alice"),
                onRename = { _, _ -> },
                onChooseSelf = {},
                onContinue = {},
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Preview dark", showBackground = true, uiMode = 0x21)
@Composable
private fun PreviewStepPreviewDark() {
    app.dibs.core.ui.theme.DibsTheme {
        androidx.compose.foundation.layout.Column {
            PreviewStep(
                step = ImportStep.Preview(previewParsed, emptyMap(), "Alice", "Lisbon crew", alreadyImported = false),
                onBack = {},
                onConfirm = {},
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Preview large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun PreviewStepPreviewLargeFont() {
    app.dibs.core.ui.theme.DibsTheme {
        androidx.compose.foundation.layout.Column {
            PreviewStep(
                step = ImportStep.Preview(previewParsed, emptyMap(), null, "Lisbon crew", alreadyImported = true),
                onBack = {},
                onConfirm = {},
            )
        }
    }
}
