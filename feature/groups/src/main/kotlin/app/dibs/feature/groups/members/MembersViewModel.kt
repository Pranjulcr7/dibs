// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.crypto.HexBytes
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
import app.dibs.core.domain.sync.GroupKeyStore
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
)

data class MembersUiState(
    val members: List<MemberRow> = emptyList(),
)

/** What "Invite someone" can produce; every branch is a user-explainable state. */
sealed interface InviteResult {
    /** Show the QR / share the link; [expiresAtMillis] feeds the validity note. */
    data class Ready(val link: InviteLink, val expiresAtMillis: Long) : InviteResult

    /** Someone else claimed first and we hold no claim — an existing member must invite us. */
    data object SelfNotClaimed : InviteResult
}

@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
    private val groupKeys: GroupKeyStore,
    private val identity: IdentitySource,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    internal var wallClock: () -> Long = System::currentTimeMillis

    val uiState: StateFlow<MembersUiState> =
        combine(ledger.observeState(groupId), settingsStore.settings) { state, settings ->
            val duplicates = DuplicateMembers.candidates(state)
                .flatMap { listOf(it.first, it.second) }
                .toSet()
            val fingerprints = Handles.fingerprintsFor(state)
            MembersUiState(
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

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val INVITE_VALIDITY_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
