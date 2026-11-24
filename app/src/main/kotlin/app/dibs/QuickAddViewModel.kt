// SPDX-License-Identifier: Apache-2.0
package app.dibs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the home FAB (NFR-2: ≤ 3 taps): one group jumps straight into the
 * editor, several offer a one-tap picker, none routes to group creation.
 */
data class QuickAddTarget(val id: GroupId, val label: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
    private val identitySource: IdentitySource,
) : ViewModel() {

    val targets: StateFlow<List<QuickAddTarget>> =
        ledger.observeGroupIds().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.sorted().map { ledger.observeState(it) }) { states ->
                    states.filterNot { it.meta?.adHoc == true || it.archived == true }
                        // By name, not by label: a leading emoji must not decide the order.
                        .sortedWith(compareBy({ it.meta?.name.orEmpty().lowercase() }, { it.groupId }))
                        .map { state ->
                            QuickAddTarget(
                                id = state.groupId,
                                label = listOfNotNull(state.meta?.emoji, state.meta?.name).joinToString(" ")
                                    .ifBlank { state.groupId.value.take(GROUP_ID_LABEL_LENGTH) },
                            )
                        }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /**
     * FR-G5: "just this once" — a hidden ad-hoc group so one-off splits reuse
     * the exact same code path as everything else.
     */
    suspend fun createAdHocGroup(groupName: String, fallbackSelfName: String): GroupId {
        val settings = settingsStore.settings.first()
        val groupId = GroupId(java.util.UUID.randomUUID().toString())
        val selfId = MemberId(java.util.UUID.randomUUID().toString())
        ledger.append(
            groupId,
            OpPayload.CreateGroup(
                name = groupName,
                defaultCurrency = settings.defaultCurrency,
                adHoc = true,
            ),
        )
        ledger.append(
            groupId,
            OpPayload.AddMember(selfId, settings.displayName.trim().ifEmpty { fallbackSelfName }),
        )
        ledger.append(groupId, SignedPayloads.claimMember(groupId, selfId, identitySource.keyPair()))
        settingsStore.update { it.copy(selfMembers = it.selfMembers + (groupId to selfId)) }
        return groupId
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val GROUP_ID_LABEL_LENGTH = 8
    }
}
