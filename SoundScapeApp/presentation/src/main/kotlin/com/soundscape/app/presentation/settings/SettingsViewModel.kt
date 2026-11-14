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

data class SettingsUiState(
    val settings: UserSettings? = null,
    val saving: Boolean = false,
    val readFailed: Boolean = false,
    val writeFailed: Boolean = false,
) {
    val initialLoading: Boolean get() = settings == null && !readFailed
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: SettingsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null

    init { retryRead() }

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

    fun setMode(mode: ThemeMode) = save { repository.setMode(mode) }
    fun setPreset(preset: ThemePreset) = save { repository.setPreset(preset) }
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
