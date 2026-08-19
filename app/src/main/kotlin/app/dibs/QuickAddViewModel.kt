// SPDX-License-Identifier: Apache-2.0
package app.dibs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.model.GroupId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    ledger: Ledger,
) : ViewModel() {

    val targets: StateFlow<List<QuickAddTarget>> =
        ledger.observeGroupIds().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.sorted().map { ledger.observeState(it) }) { states ->
                    states.map { state ->
                        QuickAddTarget(
                            id = state.groupId,
                            label = listOfNotNull(state.meta?.emoji, state.meta?.name).joinToString(" ")
                                .ifBlank { state.groupId.value.take(GROUP_ID_LABEL_LENGTH) },
                        )
                    }.sortedBy { it.label.lowercase() }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val GROUP_ID_LABEL_LENGTH = 8
    }
}
