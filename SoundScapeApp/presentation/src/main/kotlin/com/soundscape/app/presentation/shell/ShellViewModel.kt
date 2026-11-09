package com.soundscape.app.presentation.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShellUiState(val applicationName: String)

@HiltViewModel
class ShellViewModel @Inject constructor(shellInfo: ShellInfo) : ViewModel() {
    private val mutableState = MutableStateFlow(ShellUiState(shellInfo.applicationName))
    val state: StateFlow<ShellUiState> = mutableState.asStateFlow()
}
