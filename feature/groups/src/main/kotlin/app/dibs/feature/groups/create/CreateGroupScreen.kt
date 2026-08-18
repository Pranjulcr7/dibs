// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R

private val EmojiChoices = listOf("🏠", "✈️", "🍕", "🎉", "⛺", "🚗", "💼", "🃏")
private val CurrencyChoices = listOf(
    "USD", "EUR", "GBP", "INR", "JPY", "KRW", "AUD", "CAD", "CHF", "BRL", "MXN", "KWD", "BHD", "AED", "SGD",
).map(::CurrencyCode)

@Composable
fun CreateGroupRoute(
    onCreated: (GroupId) -> Unit,
    onBack: () -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val fallbackSelfName = stringResource(R.string.create_group_self_fallback)
    LaunchedEffect(Unit) {
        viewModel.created.collect(onCreated)
    }
    CreateGroupScreen(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onEmojiChange = viewModel::onEmojiChange,
        onCurrencyChange = viewModel::onCurrencyChange,
        onCreate = { viewModel.create(fallbackSelfName) },
        onBack = onBack,
    )
}

/** FR-G1: name, optional emoji, default currency. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    uiState: CreateGroupUiState,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String?) -> Unit,
    onCurrencyChange: (CurrencyCode) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_group_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.create_group_back),
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
                .padding(Dimens.spacingMd),
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.create_group_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create-group-name"),
            )
            Spacer(Modifier.height(Dimens.spacingLg))

            Text(stringResource(R.string.create_group_emoji_label))
            Spacer(Modifier.height(Dimens.spacingSm))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                FilterChip(
                    selected = uiState.emoji == null,
                    onClick = { onEmojiChange(null) },
                    label = { Text(stringResource(R.string.create_group_emoji_none)) },
                )
            }
            Spacer(Modifier.height(Dimens.spacingXs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                EmojiChoices.forEach { emoji ->
                    FilterChip(
                        selected = uiState.emoji == emoji,
                        onClick = { onEmojiChange(emoji) },
                        label = { Text(emoji) },
                    )
                }
            }
            Spacer(Modifier.height(Dimens.spacingLg))

            CurrencyDropdown(
                selected = uiState.currency,
                onSelect = onCurrencyChange,
            )
            Spacer(Modifier.height(Dimens.spacingXl))

            Button(
                onClick = onCreate,
                enabled = uiState.canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create-group-confirm"),
            ) {
                Text(stringResource(R.string.create_group_confirm))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyDropdown(selected: CurrencyCode?, onSelect: (CurrencyCode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.value ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.create_group_currency_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CurrencyChoices.forEach { currency ->
                DropdownMenuItem(
                    text = { Text(currency.value) },
                    onClick = {
                        onSelect(currency)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun CreateGroupPreviewLight() {
    DibsTheme { CreateGroupScreen(CreateGroupUiState(name = "Lisbon"), {}, {}, {}, {}, {}) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun CreateGroupPreviewDark() {
    DibsTheme { CreateGroupScreen(CreateGroupUiState(), {}, {}, {}, {}, {}) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun CreateGroupPreviewLargeFont() {
    DibsTheme { CreateGroupScreen(CreateGroupUiState(), {}, {}, {}, {}, {}) }
}
