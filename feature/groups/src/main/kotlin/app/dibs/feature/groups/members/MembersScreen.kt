// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.model.MemberId
import app.dibs.core.ui.components.MemberAvatar
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R

@Composable
fun MembersRoute(onBack: () -> Unit, viewModel: MembersViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    MembersScreen(
        uiState = uiState,
        onBack = onBack,
        onAddMember = viewModel::addMember,
        onRenameMember = viewModel::renameMember,
        onDeactivateMember = viewModel::deactivateMember,
        onReactivateMember = viewModel::reactivateMember,
    )
}

/**
 * FR-G2/G3: members are names, added freely; "removal" is deactivation so
 * history survives (edge 13). Duplicate names get a visible hint and distinct
 * avatar colors, never an automatic merge (edges 12/26).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    uiState: MembersUiState,
    onBack: () -> Unit,
    onAddMember: (String) -> Unit,
    onRenameMember: (MemberId, String) -> Unit,
    onDeactivateMember: (MemberId) -> Unit,
    onReactivateMember: (MemberId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.members_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.spacingMd),
        ) {
            AddMemberRow(onAddMember)
            Spacer(Modifier.height(Dimens.spacingMd))
            val (active, inactive) = uiState.members.partition { it.active }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(active, key = { it.id.value }) { member ->
                    MemberRowItem(member, onRenameMember, onDeactivateMember, onReactivateMember)
                }
                if (inactive.isNotEmpty()) {
                    item(key = "inactive-header") {
                        Column {
                            Spacer(Modifier.height(Dimens.spacingMd))
                            Text(
                                stringResource(R.string.members_inactive_header),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.members_deactivate_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(inactive, key = { it.id.value }) { member ->
                        MemberRowItem(member, onRenameMember, onDeactivateMember, onReactivateMember)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMemberRow(onAddMember: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.members_add_label)) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .testTag("member-add-name"),
        )
        Spacer(Modifier.width(Dimens.spacingSm))
        Button(
            onClick = {
                onAddMember(name)
                name = ""
            },
            enabled = name.isNotBlank(),
        ) {
            Text(stringResource(R.string.members_add_button))
        }
    }
}

@Composable
private fun MemberRowItem(
    member: MemberRow,
    onRenameMember: (MemberId, String) -> Unit,
    onDeactivateMember: (MemberId) -> Unit,
    onReactivateMember: (MemberId) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(name = member.name, memberId = member.id)
        Spacer(Modifier.width(Dimens.spacingMd))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, style = MaterialTheme.typography.bodyLarge)
                member.fingerprint?.let { digits ->
                    Spacer(Modifier.width(Dimens.spacingXs))
                    Text(
                        text = "#" + digits,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (member.isSelf) {
                    Spacer(Modifier.width(Dimens.spacingSm))
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.detail_you)) })
                }
            }
            if (member.duplicateName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(Dimens.spacingXs))
                    Text(
                        stringResource(R.string.members_duplicate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.members_actions_for, member.name),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.members_rename)) },
                onClick = {
                    menuOpen = false
                    renaming = true
                },
            )
            if (member.active) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.members_deactivate)) },
                    onClick = {
                        menuOpen = false
                        onDeactivateMember(member.id)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.members_reactivate)) },
                    onClick = {
                        menuOpen = false
                        onReactivateMember(member.id)
                    },
                )
            }
        }
    }
    if (renaming) {
        RenameDialog(
            current = member.name,
            onSave = { newName ->
                onRenameMember(member.id, newName)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.members_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.members_rename_label)) },
                singleLine = true,
                modifier = Modifier.testTag("member-rename-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.edit_group_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_group_cancel)) }
        },
    )
}
