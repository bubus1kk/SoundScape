package com.soundscape.app.presentation.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Inherits the application theme; Scaffold retains ownership of system insets. */
@Composable
fun ShellScreen(state: ShellUiState, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Text(
            text = state.applicationName,
            modifier = Modifier.padding(padding),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
