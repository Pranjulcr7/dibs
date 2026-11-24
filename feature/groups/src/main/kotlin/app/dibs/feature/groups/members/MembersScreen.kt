// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import android.icu.text.DateFormat
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.ui.components.MemberAvatar
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun MembersRoute(onBack: () -> Unit, viewModel: MembersViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<InviteResult?>(null) }
    MembersScreen(
        uiState = uiState,
        onBack = onBack,
        onAddMember = viewModel::addMember,
        onRenameMember = viewModel::renameMember,
        onDeactivateMember = viewModel::deactivateMember,
        onReactivateMember = viewModel::reactivateMember,
        onInvite = { scope.launch { invite = viewModel.createInvite() } },
        management = MemberManagementActions(
            onRevokeInvite = viewModel::revokeInvite,
            onSetApprovalRequired = viewModel::setApprovalRequired,
            onApproveMember = viewModel::approveMember,
            onRemoveMember = viewModel::removeMember,
        ),
    )
    when (val current = invite) {
        is InviteResult.Ready -> InviteDialog(
            link = current.link,
            expiresAtMillis = current.expiresAtMillis,
            onShare = {
                // One file carrying the link and the group's data: the person
                // joining opens it and is in, with nothing else to send them.
                scope.launch {
                    val bytes = viewModel.inviteFile(current.link)
                    val uri = withContext(Dispatchers.IO) { InviteSharing.stage(context, bytes) }
                    context.startActivity(InviteSharing.shareIntent(context, uri))
                }
            },
            onDismiss = { invite = null },
        )
        InviteResult.SelfNotClaimed -> AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text(stringResource(R.string.invite_not_claimed_title)) },
            text = { Text(stringResource(R.string.invite_not_claimed_body)) },
            confirmButton = {
                TextButton(onClick = { invite = null }) { Text(stringResource(R.string.invite_done)) }
            },
        )
        null -> Unit
    }
}

/** The signed membership actions (SPEC §5.9), bundled so the screen stays previewable. */
data class MemberManagementActions(
    val onRevokeInvite: (InviteId) -> Unit = {},
    val onSetApprovalRequired: (Boolean) -> Unit = {},
    val onApproveMember: (MemberId) -> Unit = {},
    val onRemoveMember: (MemberId) -> Unit = {},
)

/**
 * QR + link for one invite (SPEC §5.9). The QR is rendered locally with zxing —
 * the link (which carries the group key) never touches any service.
 */
@Composable
private fun InviteDialog(link: InviteLink, expiresAtMillis: Long, onShare: () -> Unit, onDismiss: () -> Unit) {
    val encoded = link.encode()
    val qr = remember(encoded) { qrBitmap(encoded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.invite_qr_description),
                    modifier = Modifier.size(Dimens.inviteQrSize),
                )
                Spacer(Modifier.height(Dimens.spacingSm))
                Text(
                    stringResource(
                        R.string.invite_validity,
                        DateFormat.getDateInstance().format(Date(expiresAtMillis)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.invite_next_step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare, modifier = Modifier.testTag("invite-share")) {
                Text(stringResource(R.string.invite_share_link))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.invite_done)) } },
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
    onInvite: () -> Unit = {},
    management: MemberManagementActions = MemberManagementActions(),
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
                actions = {
                    TextButton(onClick = onInvite, modifier = Modifier.testTag("invite-action")) {
                        Text(stringResource(R.string.invite_action))
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
                    MemberRowItem(member, onRenameMember, onDeactivateMember, onReactivateMember, management)
                }
                if (uiState.canManage) {
                    item(key = "joining") { JoiningSection(uiState, management) }
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
                        MemberRowItem(member, onRenameMember, onDeactivateMember, onReactivateMember, management)
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
    management: MemberManagementActions = MemberManagementActions(),
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
            if (member.pending) {
                MemberNote(stringResource(R.string.members_pending))
            } else if (member.joinedViaMyInvite) {
                MemberNote(stringResource(R.string.members_joined_via_invite))
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
        if (member.pending) {
            TextButton(
                onClick = { management.onApproveMember(member.id) },
                modifier = Modifier.testTag("approve-${member.id.value}"),
            ) { Text(stringResource(R.string.members_approve)) }
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
            if (member.canRemove) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.members_remove)) },
                    onClick = {
                        menuOpen = false
                        management.onRemoveMember(member.id)
                    },
                    modifier = Modifier.testTag("remove-${member.id.value}"),
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
private fun MemberNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Who may join and how: the approval switch and the invites that are still usable. */
@Composable
private fun JoiningSection(uiState: MembersUiState, management: MemberManagementActions) {
    Column(Modifier.padding(top = Dimens.spacingMd)) {
        Text(
            stringResource(R.string.members_joining_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = uiState.approvalRequired,
                    role = Role.Switch,
                    onValueChange = management.onSetApprovalRequired,
                )
                .padding(vertical = Dimens.spacingSm)
                .testTag("approval-toggle"),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.members_approval_title), style = MaterialTheme.typography.bodyLarge)
                MemberNote(stringResource(R.string.members_approval_body))
            }
            // The row is the control; the switch only shows its state.
            Switch(checked = uiState.approvalRequired, onCheckedChange = null)
        }
        uiState.openInvites.forEach { invite ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.members_invite_open,
                        DateFormat.getDateInstance().format(Date(invite.expiresAtMillis)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { management.onRevokeInvite(invite.id) },
                    modifier = Modifier.testTag("revoke-${invite.id.value}"),
                ) { Text(stringResource(R.string.members_invite_revoke)) }
            }
        }
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
