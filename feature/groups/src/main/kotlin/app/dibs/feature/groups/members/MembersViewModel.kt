// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.DuplicateMembers
import app.dibs.core.domain.ledger.Handles
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

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

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
