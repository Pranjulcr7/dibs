// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.DuplicateMembers
import app.dibs.core.domain.ledger.Handles
import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.InviteFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class MemberRow(
    val id: MemberId,
    val name: String,
    val active: Boolean,
    val isSelf: Boolean,
    /** Edge 12: same-name members are flagged, never merged automatically. */
    val duplicateName: Boolean,
    /**
     * Fingerprint digits for claimed members (SPEC §5.9) — always shown, in a
     * muted monospace so the *name* never looks verified; only this is.
     */
    val fingerprint: String? = null,
    /** Claimed under approval mode and not yet approved (SPEC §5.9). */
    val pending: Boolean = false,
    /** They came in through an invite this device's user created — the "joined via your invite" notice. */
    val joinedViaMyInvite: Boolean = false,
    /**
     * Removal is offered only where the fold will accept it: no financial
     * history (edge 13), and never yourself.
     */
    val canRemove: Boolean = false,
)

/** An invite that can still be used. */
data class InviteRow(
    val id: InviteId,
    val expiresAtMillis: Long,
    val usesLeft: Int,
    val createdByMe: Boolean,
)

data class MembersUiState(
    val members: List<MemberRow> = emptyList(),
    val openInvites: List<InviteRow> = emptyList(),
    val approvalRequired: Boolean = false,
    /**
     * Whether this device's identity is a full member — the fold's condition
     * for every signed membership op. When false, the management controls are
     * hidden rather than shown and refused.
     */
    val canManage: Boolean = false,
)

/** What "Invite someone" can produce; every branch is a user-explainable state. */
sealed interface InviteResult {
    /** Show the QR / share the invite; [expiresAtMillis] feeds the validity note. */
    data class Ready(val link: InviteLink, val expiresAtMillis: Long) : InviteResult

    /** Someone else claimed first and we hold no claim — an existing member must invite us. */
    data object SelfNotClaimed : InviteResult

    /**
     * Onboarding was skipped, so this device's member is still called by the
     * placeholder ("You"). That is fine alone, but the moment the group is
     * shared, everyone else would see a member literally named "You". Ask for
     * a real name first; [MembersViewModel.setMyName] then renames the member.
     */
    data object NeedsName : InviteResult
}

@Suppress("TooManyFunctions") // one function per member and membership action on the screen
@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
    private val groupKeys: GroupKeyStore,
    private val identity: IdentitySource,
    private val bundleSync: BundleSync,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    internal var wallClock: () -> Long = System::currentTimeMillis

    val uiState: StateFlow<MembersUiState> =
        combine(ledger.observeState(groupId), settingsStore.settings) { state, settings ->
            val duplicates = DuplicateMembers.candidates(state)
                .flatMap { listOf(it.first, it.second) }
                .toSet()
            val fingerprints = Handles.fingerprintsFor(state)
            val myKey = identity.keyPair().publicKey
            val me = state.members.values.firstOrNull { it.identityKey == myKey && !it.removed }
            val canManage = me != null && me.active && !me.pending
            val myInvites = state.invites.values.filter { it.inviterKey == myKey }.map { it.id }.toSet()
            MembersUiState(
                openInvites = state.invites.values
                    .filter { !it.revoked && it.uses < it.maxUses }
                    .map { InviteRow(it.id, it.expiresAtMillis, it.maxUses - it.uses, it.inviterKey == myKey) }
                    .sortedWith(compareBy({ it.expiresAtMillis }, { it.id })),
                approvalRequired = state.joinApprovalRequired == true,
                canManage = canManage,
                members = state.members.values
                    .filterNot { it.removed }
                    .map { member ->
                        MemberRow(
                            id = member.id,
                            name = member.name,
                            active = member.active,
                            isSelf = settings.selfMembers[groupId] == member.id,
                            duplicateName = member.id in duplicates,
                            fingerprint = fingerprints[member.id],
                            pending = member.pending,
                            joinedViaMyInvite = member.claimedViaInvite in myInvites,
                            canRemove = canManage && member.id != me?.id && !state.memberHasHistory(member.id),
                        )
                    }
                    .sortedWith(
                        compareByDescending<MemberRow> { it.active }
                            .thenByDescending { it.isSelf }
                            .thenBy { it.name.lowercase() }
                            .thenBy { it.id },
                    ),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), MembersUiState())

    fun addMember(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            ledger.append(groupId, OpPayload.AddMember(MemberId(UUID.randomUUID().toString()), trimmed))
        }
    }

    fun renameMember(memberId: MemberId, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { ledger.append(groupId, OpPayload.RenameMember(memberId, trimmed)) }
    }

    /** Deactivation, never deletion — history must survive (SPEC §6.2 case 13). */
    fun deactivateMember(memberId: MemberId) {
        viewModelScope.launch { ledger.append(groupId, OpPayload.DeactivateMember(memberId)) }
    }

    fun reactivateMember(memberId: MemberId) {
        viewModelScope.launch { ledger.append(groupId, OpPayload.ReactivateMember(memberId)) }
    }

    /**
     * Creates a single-use, 7-day invite (SPEC §5.9) and the `dibs://` link
     * carrying the group key. Claims our own member first when nobody in the
     * group is claimed yet — the fold's bootstrap rule — because only a
     * claimed member may sign a `CreateInvite`. If others are claimed and we
     * are not, inviting is not ours to do: [InviteResult.SelfNotClaimed].
     */
    suspend fun createInvite(): InviteResult {
        if (settingsStore.settings.first().displayName.isBlank()) return InviteResult.NeedsName
        val state = ledger.state(groupId)
        val keyPair = identity.keyPair()
        val claimedKeys = state.members.values.filterNot { it.removed }.mapNotNull { it.identityKey }
        if (keyPair.publicKey !in claimedKeys) {
            if (claimedKeys.isNotEmpty()) return InviteResult.SelfNotClaimed
            // Bootstrap: claim our own member so the invite signature validates.
            // Read from settings, not from uiState: that flow only runs while the
            // screen is subscribed, and an invite must not depend on UI timing.
            val selfId = settingsStore.settings.first().selfMembers[groupId]
                ?.takeIf { id -> state.members[id]?.removed == false }
                ?: return InviteResult.SelfNotClaimed
            ledger.append(groupId, SignedPayloads.claimMember(groupId, selfId, keyPair))
        }
        val inviteId = InviteId(UUID.randomUUID().toString())
        val expiresAtMillis = wallClock() + INVITE_VALIDITY_MILLIS
        val payload = SignedPayloads.createInvite(
            groupId = groupId,
            inviteId = inviteId,
            inviter = keyPair,
            maxUses = 1,
            expiresAtMillis = expiresAtMillis,
        )
        ledger.append(groupId, payload)
        val link = InviteLink(
            groupId = groupId,
            groupKey = groupKeys.getOrCreate(groupId),
            inviteId = inviteId,
            signature = HexBytes.fromHex(payload.signature),
        )
        return InviteResult.Ready(link, expiresAtMillis)
    }

    /**
     * Records the user's name and renames their member in this group to it —
     * the answer to [InviteResult.NeedsName]. Other groups keep whatever name
     * they have; this is about the group being shared right now.
     */
    suspend fun setMyName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        settingsStore.update { it.copy(displayName = trimmed) }
        val selfId = settingsStore.settings.first().selfMembers[groupId] ?: return
        val current = ledger.state(groupId).members[selfId] ?: return
        if (current.name != trimmed) ledger.append(groupId, OpPayload.RenameMember(selfId, trimmed))
    }

    /**
     * The invite as one file: the link in front of the sealed group (FR-Y1).
     * Whoever opens it with Dibs can join in one step, with the group's
     * contents already there — see [InviteFile].
     */
    suspend fun inviteFile(link: InviteLink): ByteArray = InviteFile.encode(link, bundleSync.exportBundle(groupId))

    /** Closes an invite nobody should use any more — a link sent to the wrong chat, say. */
    fun revokeInvite(inviteId: InviteId) = signed { keys -> SignedPayloads.revokeInvite(groupId, inviteId, keys) }

    /** SPEC §5.9 approval mode: new joiners wait until a member approves them. */
    fun setApprovalRequired(required: Boolean) =
        signed { keys -> SignedPayloads.setJoinPolicy(groupId, required, keys) }

    fun approveMember(memberId: MemberId) = signed { keys -> SignedPayloads.approveMember(groupId, memberId, keys) }

    /**
     * Removes a member with no history — the answer to "who is this that
     * joined via my invite?". Members with history can only be deactivated;
     * the row never offers this for them, and the fold would refuse it anyway.
     */
    fun removeMember(memberId: MemberId) = signed { keys -> SignedPayloads.removeMember(groupId, memberId, keys) }

    private fun signed(build: (IdentityKeyPair) -> OpPayload) {
        viewModelScope.launch { ledger.append(groupId, build(identity.keyPair())) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val INVITE_VALIDITY_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
