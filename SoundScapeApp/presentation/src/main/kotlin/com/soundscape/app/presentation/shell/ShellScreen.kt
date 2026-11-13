package com.soundscape.app.presentation.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Inherits the application theme; Scaffold retains ownership of system insets. */
@Composable
fun ShellScreen(state: ShellUiState, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding)) {
            Text(text = state.applicationName, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}
