// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.trash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.ui.components.EmptyState
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R

@Composable
fun TrashRoute(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    TrashScreen(rows = rows, onBack = onBack, onRestore = viewModel::restore)
}

/** FR-E5: deleted expenses, restorable, with the 30-day window made visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    rows: List<TrashRow>?,
    onBack: () -> Unit,
    onRestore: (ExpenseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            rows == null -> Unit
            rows.isEmpty() -> EmptyState(
                title = stringResource(R.string.trash_empty_title),
                body = stringResource(R.string.trash_empty_body),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.spacingMd),
            ) {
                items(rows, key = { it.expense.id.value }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.spacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.expense.fields.description, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                MoneyFormatter.format(row.expense.fields.total, row.expense.fields.currency) +
                                    " · " + stringResource(R.string.trash_days_left, row.daysLeft),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onRestore(row.expense.id) }) {
                            Text(stringResource(R.string.trash_restore))
                        }
                    }
                }
            }
        }
    }
}
