package com.soundscape.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundscape.app.presentation.shell.ShellScreen
import com.soundscape.app.presentation.shell.ShellViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shellViewModel = ViewModelProvider(this)[ShellViewModel::class.java]
        setContent {
            val state = shellViewModel.state.collectAsStateWithLifecycle()
            ShellScreen(state.value)
        }
    }
}
