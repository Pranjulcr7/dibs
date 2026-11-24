// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.join

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.InviteFile
import app.dibs.core.domain.sync.JoinCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** A name-only member the joiner may turn out to be. */
data class ClaimableMember(val id: MemberId, val name: String)

/**
 * Every way joining or opening a Dibs file can go wrong, each with its own
 * explanation on screen (SPEC §4.2: no generic "something went wrong").
 */
enum class JoinProblem {
    /** The text is not a Dibs invite link. */
    INVALID_LINK,

    /** We already hold a different key for this group: this link is for something else. */
    DIFFERENT_GROUP_KEY,

    /** The opened file is neither an invite nor an update. */
    NOT_A_DIBS_FILE,

    /** An update for a group this device has never been invited to. */
    NO_KEY_FOR_GROUP,

    NEEDS_NEWER_APP,

    /** Damaged in transit, or sealed for a different group (edge 27). */
    DAMAGED_OR_WRONG_GROUP,

    BAD_SIGNATURE,

    /** Sealed by someone who is not a member of the group (AUDIT T1). */
    UNKNOWN_SENDER,

    INVITE_REVOKED,
    INVITE_EXPIRED,
    INVITE_USED_UP,

    /** Our claim was written but another device's claim won the race in the fold. */
    CLAIM_REJECTED,
}

sealed interface JoinUiState {

    /** Opened from "Join a group" with nothing yet: paste a link, scan a code, or open a file. */
    data object EnterLink : JoinUiState

    data object Working : JoinUiState

    data class Problem(val problem: JoinProblem) : JoinUiState

    /**
     * The key is in hand but the group's contents are not (a bare link or QR),
     * or they predate this invite ([stale]). The inviter needs to share updates.
     */
    data class NeedsUpdates(val stale: Boolean) : JoinUiState

    data class ChooseMember(
        val groupName: String?,
        val inviterName: String?,
        val claimable: List<ClaimableMember>,
        val suggestedName: String,
        val approvalRequired: Boolean,
    ) : JoinUiState

    /**
     * Membership exists on this device. [justJoined] means the claim was
     * written now and nobody else knows yet — the screen pushes the joiner to
     * send it back.
     */
    data class Joined(
        val groupId: GroupId,
        val groupName: String?,
        val pendingApproval: Boolean,
        val justJoined: Boolean,
    ) : JoinUiState

    /** A plain update file for a group we are already in. */
    data class UpdatesImported(
        val groupId: GroupId,
        val groupName: String?,
        val opsAdded: Int,
        val mayBeMissing: Boolean,
    ) : JoinUiState
}

/**
 * Joining a group and opening Dibs files (FR-Y1, SPEC §5.9): the one place a
 * `dibs://join` link, a scanned QR code, an invite file, or an update file
 * lands when it arrives from outside the app.
 *
 * The order of operations is the security-relevant part:
 * 1. The link is parsed strictly; anything malformed stops here.
 * 2. The group key is adopted — a *different* key for a group we already hold
 *    is an error, never an overwrite.
 * 3. Any bundle is imported through [BundleSync], which authenticates and
 *    applies it wholesale or not at all.
 * 4. [JoinCheck] mirrors the fold's invite rules so nothing is appended that
 *    every device would quarantine.
 * 5. Only an explicit user choice writes the signed `ClaimMember`, and the
 *    result is confirmed against the fold, because a concurrent claim on
 *    another device can still win.
 */
@Suppress("TooManyFunctions") // one entry point per way an invite or file arrives, plus the join steps
@HiltViewModel
class JoinViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
    private val groupKeys: GroupKeyStore,
    private val identity: IdentitySource,
    private val bundleSync: BundleSync,
) : ViewModel() {

    internal var wallClock: () -> Long = System::currentTimeMillis

    private val _uiState = MutableStateFlow<JoinUiState>(JoinUiState.EnterLink)
    val uiState: StateFlow<JoinUiState> = _uiState.asStateFlow()

    /**
     * The invite being acted on, kept as text in saved state so a process
     * death between "open link" and "pick who you are" resumes the join
     * instead of losing it (edge 61). The key itself is already in the
     * encrypted store by then.
     */
    private var pendingLink: InviteLink?
        get() = savedStateHandle.get<String>(KEY_LINK)?.let(InviteLink::parse)
        set(value) {
            savedStateHandle[KEY_LINK] = value?.encode()
        }

    init {
        // A link handed over as a navigation argument, or one restored after process death.
        val initial = savedStateHandle.get<String>(ARG_LINK)?.takeIf { it.isNotBlank() }
            ?: savedStateHandle.get<String>(KEY_LINK)
        if (initial != null) openLink(initial)
    }

    /** A pasted link, a scanned QR code, or a `dibs://join` deep link. */
    fun openLink(text: String) {
        val link = InviteLink.parse(text.trim())
        if (link == null) {
            _uiState.value = JoinUiState.Problem(JoinProblem.INVALID_LINK)
            return
        }
        launchWorking {
            if (adopt(link)) {
                pendingLink = link
                evaluate(link)
            }
        }
    }

    /**
     * A file opened with Dibs or picked on this screen: an invite file (link +
     * the group's data), or a plain update file.
     */
    fun openFile(bytes: ByteArray) {
        launchWorking {
            val invite = InviteFile.decode(bytes)
            when {
                invite != null -> {
                    if (!adopt(invite.link)) return@launchWorking
                    pendingLink = invite.link
                    val outcome = bundleSync.importBundle(invite.bundle)
                    if (outcome is BundleSync.ImportOutcome.Imported) {
                        evaluate(invite.link)
                    } else {
                        _uiState.value = JoinUiState.Problem(outcome.asProblem())
                    }
                }
                InviteFile.looksLikeInviteFile(bytes) ->
                    _uiState.value = JoinUiState.Problem(JoinProblem.DAMAGED_OR_WRONG_GROUP)
                else -> importUpdates(bytes)
            }
        }
    }

    private suspend fun importUpdates(bytes: ByteArray) {
        val outcome = bundleSync.importBundle(bytes)
        if (outcome !is BundleSync.ImportOutcome.Imported) {
            _uiState.value = JoinUiState.Problem(outcome.asProblem())
            return
        }
        val link = pendingLink
        if (link != null && link.groupId == outcome.groupId) {
            // The updates a bare link was waiting for: carry on with the join.
            evaluate(link)
        } else {
            _uiState.value = JoinUiState.UpdatesImported(
                groupId = outcome.groupId,
                groupName = ledger.state(outcome.groupId).meta?.name,
                opsAdded = outcome.opsAdded,
                mayBeMissing = outcome.behind.isNotEmpty(),
            )
        }
    }

    /** "That's me": bind our identity to a member someone already added by name. */
    fun claimExisting(memberId: MemberId) = claim(memberId, displayName = null)

    /** "I'm not on the list": join as a new member. */
    fun joinAsNew(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        claim(MemberId(UUID.randomUUID().toString()), displayName = trimmed)
    }

    /**
     * Seals the group, our fresh claim included, for the share sheet. Until
     * someone in the group opens it, only this device knows we joined — there
     * is no server to tell them.
     */
    suspend fun exportJoinBundle(): ByteArray? {
        val joined = _uiState.value as? JoinUiState.Joined ?: return null
        return bundleSync.exportBundle(joined.groupId)
    }

    /** Back to the start from a problem the user can retry (a mistyped link, the wrong file). */
    fun reset() {
        pendingLink = null
        _uiState.value = JoinUiState.EnterLink
    }

    // -- internals ------------------------------------------------------------

    private fun launchWorking(block: suspend () -> Unit) {
        _uiState.value = JoinUiState.Working
        viewModelScope.launch { block() }
    }

    /** False (with the problem shown) when this device holds a different key for the group. */
    private suspend fun adopt(link: InviteLink): Boolean = try {
        groupKeys.adopt(link.groupId, link.groupKey)
        true
    } catch (_: IllegalStateException) {
        // GroupKeyStore's documented refusal: never resolve a key conflict silently.
        _uiState.value = JoinUiState.Problem(JoinProblem.DIFFERENT_GROUP_KEY)
        false
    }

    private suspend fun evaluate(link: InviteLink) {
        val state = ledger.state(link.groupId)
        val myKey = identity.keyPair().publicKey
        _uiState.value = when (val check = JoinCheck.evaluate(state, link.inviteId, myKey, wallClock())) {
            JoinCheck.Result.NeedsUpdates -> JoinUiState.NeedsUpdates(stale = false)
            JoinCheck.Result.InviteNotInData -> JoinUiState.NeedsUpdates(stale = true)
            JoinCheck.Result.InviteRevoked -> JoinUiState.Problem(JoinProblem.INVITE_REVOKED)
            JoinCheck.Result.InviteExpired -> JoinUiState.Problem(JoinProblem.INVITE_EXPIRED)
            JoinCheck.Result.InviteUsedUp -> JoinUiState.Problem(JoinProblem.INVITE_USED_UP)
            is JoinCheck.Result.AlreadyMember -> {
                rememberSelf(link.groupId, check.memberId, displayName = null)
                JoinUiState.Joined(link.groupId, state.meta?.name, check.pending, justJoined = false)
            }
            is JoinCheck.Result.Ready -> JoinUiState.ChooseMember(
                groupName = check.groupName,
                inviterName = check.inviterName,
                claimable = check.claimable
                    .map { ClaimableMember(it.id, it.name) }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.id })),
                suggestedName = settingsStore.settings.first().displayName,
                approvalRequired = check.approvalRequired,
            )
        }
    }

    private fun claim(memberId: MemberId, displayName: String?) {
        val link = pendingLink ?: return
        if (_uiState.value !is JoinUiState.ChooseMember) return
        launchWorking {
            val keyPair = identity.keyPair()
            // Re-check at the moment of writing: updates may have arrived since the screen was drawn.
            val check = JoinCheck.evaluate(ledger.state(link.groupId), link.inviteId, keyPair.publicKey, wallClock())
            if (check !is JoinCheck.Result.Ready) {
                evaluate(link)
                return@launchWorking
            }
            ledger.append(
                link.groupId,
                SignedPayloads.claimMember(link.groupId, memberId, keyPair, displayName, link.inviteId),
            )
            // The fold is the authority: confirm the claim actually applied.
            val after = ledger.state(link.groupId)
            val member = after.members[memberId]
            if (member?.identityKey != keyPair.publicKey) {
                _uiState.value = JoinUiState.Problem(JoinProblem.CLAIM_REJECTED)
                return@launchWorking
            }
            rememberSelf(link.groupId, memberId, displayName)
            _uiState.value = JoinUiState.Joined(link.groupId, after.meta?.name, member.pending, justJoined = true)
        }
    }

    /** D23: which member is "me" is device-local; a first join also seeds an empty display name. */
    private suspend fun rememberSelf(groupId: GroupId, memberId: MemberId, displayName: String?) {
        settingsStore.update { settings ->
            settings.copy(
                selfMembers = settings.selfMembers + (groupId to memberId),
                displayName = settings.displayName.ifBlank { displayName.orEmpty() },
            )
        }
    }

    private fun BundleSync.ImportOutcome.asProblem(): JoinProblem = when (this) {
        is BundleSync.ImportOutcome.Imported -> error("Imported is not a problem")
        is BundleSync.ImportOutcome.NoKeyForGroup -> JoinProblem.NO_KEY_FOR_GROUP
        BundleSync.ImportOutcome.NotABundle -> JoinProblem.NOT_A_DIBS_FILE
        is BundleSync.ImportOutcome.NeedsNewerApp -> JoinProblem.NEEDS_NEWER_APP
        BundleSync.ImportOutcome.RejectedTamperedOrWrongKey -> JoinProblem.DAMAGED_OR_WRONG_GROUP
        BundleSync.ImportOutcome.RejectedBadSignature -> JoinProblem.BAD_SIGNATURE
        is BundleSync.ImportOutcome.RejectedUnknownSender -> JoinProblem.UNKNOWN_SENDER
    }

    companion object {
        /** Navigation argument: an invite link handed in from a deep link or QR scan. */
        const val ARG_LINK = "link"
        private const val KEY_LINK = "pendingInviteLink"
    }
}
