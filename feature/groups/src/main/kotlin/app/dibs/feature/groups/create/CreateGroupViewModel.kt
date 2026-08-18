// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CreateGroupUiState(
    val name: String = "",
    val emoji: String? = null,
    val currency: CurrencyCode? = null,
    val creating: Boolean = false,
) {
    val canCreate: Boolean get() = name.isNotBlank() && !creating
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    private val _created = MutableSharedFlow<GroupId>(replay = 1)
    val created: SharedFlow<GroupId> = _created.asSharedFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun onEmojiChange(value: String?) = _uiState.update { it.copy(emoji = value?.takeUnless(String::isBlank)) }

    fun onCurrencyChange(value: CurrencyCode) = _uiState.update { it.copy(currency = value) }

    /**
     * Creates the group and its first member — "you". [fallbackSelfName] comes
     * from resources because "You" is user-facing text; it is used when
     * onboarding was skipped and no display name exists. The self-member
     * mapping is stored device-locally (DECISIONS.md D23).
     */
    fun create(fallbackSelfName: String) {
        val form = _uiState.value
        if (!form.canCreate) return
        _uiState.update { it.copy(creating = true) }
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val groupId = GroupId(UUID.randomUUID().toString())
            val selfId = MemberId(UUID.randomUUID().toString())
            ledger.append(
                groupId,
                OpPayload.CreateGroup(
                    name = form.name.trim(),
                    emoji = form.emoji,
                    colorArgb = null,
                    defaultCurrency = form.currency ?: settings.defaultCurrency,
                ),
            )
            ledger.append(
                groupId,
                OpPayload.AddMember(selfId, settings.displayName.trim().ifEmpty { fallbackSelfName }),
            )
            settingsStore.update { it.copy(selfMembers = it.selfMembers + (groupId to selfId)) }
            _created.emit(groupId)
        }
    }
}
