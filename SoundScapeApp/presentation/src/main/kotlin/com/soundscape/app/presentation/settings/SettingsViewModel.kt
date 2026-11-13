package com.soundscape.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Null settings means no persisted value has been loaded yet. */
data class SettingsUiState(
    val settings: UserSettings? = null,
    val saving: Boolean = false,
    val readFailed: Boolean = false,
    val writeFailed: Boolean = false,
) {
    /** Initial disk read is pending; an explicit error must release the launch screen. */
    val initialLoading: Boolean get() = settings == null && !readFailed
}

/** Observes persisted values rather than applying optimistic, unconfirmed selections. */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null

    init { retryRead() }

    /** Retries a failed read without overwriting the underlying preferences file. */
    fun retryRead() {
        observer?.cancel()
        mutableState.update { it.copy(readFailed = false) }
        observer = viewModelScope.launch {
            try {
                repository.settings.collect { settings ->
                    mutableState.update { it.copy(settings = settings, readFailed = false) }
                }
            } catch (_: IOException) {
                mutableState.update { it.copy(readFailed = true) }
            }
        }
    }

    /** Persists the selected brightness mode. */
    fun setMode(mode: ThemeMode) = save { repository.setMode(mode) }
    /** Persists the selected palette without changing dynamic color preference. */
    fun setPreset(preset: ThemePreset) = save { repository.setPreset(preset) }
    /** Persists whether Android dynamic colors should be used when supported. */
    fun setDynamicColor(enabled: Boolean) = save { repository.setDynamicColor(enabled) }

    private fun save(action: suspend () -> Unit) {
        if (state.value.settings == null || state.value.saving || state.value.readFailed) return
        mutableState.update { it.copy(saving = true, writeFailed = false) }
        viewModelScope.launch {
            try {
                action()
            } catch (_: IOException) {
                mutableState.update { it.copy(writeFailed = true) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}
