// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // one small composable per join step, plus previews

package app.dibs.feature.groups.join

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.groups.R
import app.dibs.feature.groups.detail.SyncSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where an invite link, a scanned code, or a Dibs file lands (FR-Y1).
 * [incomingFile] is a content URI handed over by another app; it is read once,
 * off the main thread, with a size cap.
 */
@Composable
fun JoinRoute(
    onBack: () -> Unit,
    onOpenGroup: (GroupId) -> Unit,
    onScanCode: () -> Unit,
    modifier: Modifier = Modifier,
    incomingFile: String? = null,
    scannedLink: String? = null,
    viewModel: JoinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun open(uri: Uri) {
        scope.launch {
            when (val read = withContext(Dispatchers.IO) { IncomingFiles.read(context, uri) }) {
                is IncomingFiles.Read.Bytes -> viewModel.openFile(read.bytes)
                // Unreadable and oversized files are, to the user, "not something Dibs can open".
                IncomingFiles.Read.TooLarge, IncomingFiles.Read.Unreadable -> viewModel.openFile(ByteArray(0))
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }
    // rememberSaveable: a rotation must not re-read the file or re-open the link.
    var consumedFile by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(incomingFile) {
        if (incomingFile != null && incomingFile != consumedFile) {
            consumedFile = incomingFile
            open(Uri.parse(incomingFile))
        }
    }
    var consumedScan by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(scannedLink) {
        if (scannedLink != null && scannedLink != consumedScan) {
            consumedScan = scannedLink
            viewModel.openLink(scannedLink)
        }
    }

    JoinScreen(
        uiState = uiState,
        actions = JoinActions(
            onBack = onBack,
            onOpenLink = viewModel::openLink,
            onPickFile = { filePicker.launch(arrayOf("*/*")) },
            onScanCode = onScanCode,
            onClaim = viewModel::claimExisting,
            onJoinAsNew = viewModel::joinAsNew,
            onShareJoin = {
                scope.launch {
                    val bytes = viewModel.exportJoinBundle() ?: return@launch
                    val groupName = (uiState as? JoinUiState.Joined)?.groupName
                    val uri = withContext(Dispatchers.IO) { SyncSharing.stage(context, bytes, groupName) }
                    context.startActivity(SyncSharing.shareIntent(context, uri))
                }
            },
            onOpenGroup = onOpenGroup,
            onStartOver = viewModel::reset,
        ),
        modifier = modifier,
    )
}

/** Reads a file another app handed us, bounded so a hostile sender cannot exhaust memory (edge 29). */
internal object IncomingFiles {

    /** Far above any real group (a 5,000-expense group seals to a few MB) and safely below heap limits. */
    const val MAX_BYTES = 24 * 1024 * 1024
    private const val CHUNK = 64 * 1024

    sealed interface Read {
        class Bytes(val bytes: ByteArray) : Read
        data object TooLarge : Read
        data object Unreadable : Read
    }

    @Suppress("SwallowedException") // every failure to read is the same typed answer: Unreadable
    fun read(context: Context, uri: Uri): Read = try {
        context.contentResolver.openInputStream(uri)?.use(::readBounded) ?: Read.Unreadable
    } catch (e: java.io.IOException) {
        Read.Unreadable
    } catch (e: SecurityException) {
        // The granting app revoked access, or the URI was never ours to read.
        Read.Unreadable
    }

    /** Stops at [MAX_BYTES] + 1: enough to know the file is too large without reading the rest. */
    internal fun readBounded(input: java.io.InputStream): Read {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        var n = input.read(buffer)
        while (n >= 0 && out.size() <= MAX_BYTES) {
            out.write(buffer, 0, n)
            n = input.read(buffer)
        }
        return if (out.size() > MAX_BYTES) Read.TooLarge else Read.Bytes(out.toByteArray())
    }
}

/** Callbacks bundled so the screen stays previewable without a ViewModel. */
data class JoinActions(
    val onBack: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onPickFile: () -> Unit = {},
    val onScanCode: () -> Unit = {},
    val onClaim: (MemberId) -> Unit = {},
    val onJoinAsNew: (String) -> Unit = {},
    val onShareJoin: () -> Unit = {},
    val onOpenGroup: (GroupId) -> Unit = {},
    val onStartOver: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(uiState: JoinUiState, actions: JoinActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spacingMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        ) {
            when (uiState) {
                JoinUiState.EnterLink -> EnterLinkStep(actions)
                JoinUiState.Working -> CircularProgressIndicator(Modifier.testTag("join-working"))
                is JoinUiState.Problem -> ProblemStep(uiState.problem, actions)
                is JoinUiState.NeedsUpdates -> NeedsUpdatesStep(uiState, actions)
                is JoinUiState.ChooseMember -> ChooseMemberStep(uiState, actions)
                is JoinUiState.Joined -> JoinedStep(uiState, actions)
                is JoinUiState.UpdatesImported -> UpdatesImportedStep(uiState, actions)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun StepBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PrimaryButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouchTarget)
            .testTag(tag),
    ) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouchTarget)
            .testTag(tag),
    ) { Text(text) }
}

@Composable
private fun EnterLinkStep(actions: JoinActions) {
    var link by rememberSaveable { mutableStateOf("") }
    StepTitle(stringResource(R.string.join_enter_title))
    StepBody(stringResource(R.string.join_enter_body))
    PrimaryButton(stringResource(R.string.join_open_file), "join-open-file", onClick = actions.onPickFile)
    SecondaryButton(stringResource(R.string.join_scan_code), "join-scan", onClick = actions.onScanCode)
    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text(stringResource(R.string.join_paste_label)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("join-link-field"),
    )
    SecondaryButton(stringResource(R.string.join_continue), "join-continue") { actions.onOpenLink(link) }
}

@Composable
private fun NeedsUpdatesStep(state: JoinUiState.NeedsUpdates, actions: JoinActions) {
    StepTitle(stringResource(R.string.join_needs_updates_title))
    StepBody(
        stringResource(
            if (state.stale) R.string.join_needs_updates_stale_body else R.string.join_needs_updates_body,
        ),
    )
    PrimaryButton(stringResource(R.string.join_open_updates), "join-open-updates", onClick = actions.onPickFile)
}

@Composable
private fun ChooseMemberStep(state: JoinUiState.ChooseMember, actions: JoinActions) {
    // null selection = "I'm not on the list"; preselect it when there is nobody to claim.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf(state.suggestedName) }

    StepTitle(
        if (state.groupName.isNullOrBlank()) {
            stringResource(R.string.join_choose_title_unnamed)
        } else {
            stringResource(R.string.join_choose_title, state.groupName)
        },
    )
    if (!state.inviterName.isNullOrBlank()) StepBody(stringResource(R.string.join_invited_by, state.inviterName))

    if (state.claimable.isNotEmpty()) {
        Text(stringResource(R.string.join_which_is_you), style = MaterialTheme.typography.titleMedium)
        state.claimable.forEach { member ->
            ChoiceRow(
                label = member.name,
                selected = selected == member.id.value,
                tag = "join-member-${member.id.value}",
                onSelect = { selected = member.id.value },
            )
        }
        ChoiceRow(
            label = stringResource(R.string.join_not_listed),
            selected = selected == null,
            tag = "join-member-new",
            onSelect = { selected = null },
        )
    }
    if (selected == null) {
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text(stringResource(R.string.join_your_name)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("join-name-field"),
        )
    }
    if (state.approvalRequired) StepBody(stringResource(R.string.join_approval_note))

    PrimaryButton(
        text = stringResource(R.string.join_confirm),
        tag = "join-confirm",
        enabled = selected != null || newName.isNotBlank(),
    ) {
        val choice = selected
        if (choice == null) actions.onJoinAsNew(newName) else actions.onClaim(MemberId(choice))
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
    ) {
        // The row is the control (one TalkBack stop, 48dp tall); the button is decoration.
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Dimens.spacingSm))
    }
}

@Composable
private fun JoinedStep(state: JoinUiState.Joined, actions: JoinActions) {
    StepTitle(
        if (state.groupName.isNullOrBlank()) {
            stringResource(R.string.join_done_title_unnamed)
        } else {
            stringResource(R.string.join_done_title, state.groupName)
        },
    )
    if (state.pendingApproval) StepBody(stringResource(R.string.join_done_pending))
    if (state.justJoined) {
        StepBody(stringResource(R.string.join_done_share_body))
        PrimaryButton(stringResource(R.string.join_done_share), "join-share", onClick = actions.onShareJoin)
        SecondaryButton(stringResource(R.string.join_open_group), "join-open-group") {
            actions.onOpenGroup(state.groupId)
        }
    } else {
        StepBody(stringResource(R.string.join_already_member))
        PrimaryButton(stringResource(R.string.join_open_group), "join-open-group") {
            actions.onOpenGroup(state.groupId)
        }
    }
}

@Composable
private fun UpdatesImportedStep(state: JoinUiState.UpdatesImported, actions: JoinActions) {
    StepTitle(stringResource(R.string.join_updates_title))
    StepBody(
        if (state.opsAdded == 0) {
            stringResource(R.string.join_updates_nothing_new)
        } else {
            pluralStringResource(R.plurals.join_updates_added, state.opsAdded, state.opsAdded)
        },
    )
    if (state.mayBeMissing) StepBody(stringResource(R.string.join_updates_missing))
    PrimaryButton(stringResource(R.string.join_open_group), "join-open-group") { actions.onOpenGroup(state.groupId) }
}

@Composable
private fun ProblemStep(problem: JoinProblem, actions: JoinActions) {
    StepTitle(stringResource(R.string.join_problem_title))
    Text(
        stringResource(problem.messageRes()),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("join-problem"),
    )
    PrimaryButton(stringResource(R.string.join_try_file), "join-open-file", onClick = actions.onPickFile)
    TextButton(onClick = actions.onStartOver, modifier = Modifier.testTag("join-start-over")) {
        Text(stringResource(R.string.join_start_over))
    }
}

/** One specific sentence per problem (SPEC §4.2): what happened and what to do next. */
internal fun JoinProblem.messageRes(): Int = when (this) {
    JoinProblem.INVALID_LINK -> R.string.join_problem_invalid_link
    JoinProblem.DIFFERENT_GROUP_KEY -> R.string.join_problem_different_key
    JoinProblem.NOT_A_DIBS_FILE -> R.string.join_problem_not_dibs
    JoinProblem.NO_KEY_FOR_GROUP -> R.string.sync_no_key
    JoinProblem.NEEDS_NEWER_APP -> R.string.sync_needs_newer_app
    JoinProblem.DAMAGED_OR_WRONG_GROUP -> R.string.sync_rejected_tampered
    JoinProblem.BAD_SIGNATURE -> R.string.sync_rejected_signature
    JoinProblem.UNKNOWN_SENDER -> R.string.sync_rejected_unknown_sender
    JoinProblem.INVITE_REVOKED -> R.string.join_problem_revoked
    JoinProblem.INVITE_EXPIRED -> R.string.join_problem_expired
    JoinProblem.INVITE_USED_UP -> R.string.join_problem_used_up
    JoinProblem.CLAIM_REJECTED -> R.string.join_problem_claim_rejected
}

private val previewChoose = JoinUiState.ChooseMember(
    groupName = "Goa trip",
    inviterName = "Priya",
    claimable = listOf(
        ClaimableMember(MemberId("0b000000-0000-4000-8000-000000000002"), "Sam"),
        ClaimableMember(MemberId("0c000000-0000-4000-8000-000000000003"), "Zoe"),
    ),
    suggestedName = "Sam",
    approvalRequired = true,
)

@Preview(showBackground = true)
@Composable
private fun JoinPreviewLight() {
    DibsTheme(dynamicColor = false) { JoinScreen(previewChoose, JoinActions()) }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JoinPreviewDark() {
    DibsTheme(dynamicColor = false) {
        JoinScreen(
            JoinUiState.Joined(GroupId("11111111-0000-4000-8000-000000000000"), "Goa trip", false, true),
            JoinActions(),
        )
    }
}

@Preview(showBackground = true, fontScale = 2f)
@Composable
private fun JoinPreviewLargeFont() {
    DibsTheme(dynamicColor = false) { JoinScreen(JoinUiState.NeedsUpdates(stale = false), JoinActions()) }
}
