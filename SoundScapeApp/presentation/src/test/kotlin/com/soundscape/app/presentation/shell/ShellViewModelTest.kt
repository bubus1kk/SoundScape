package com.soundscape.app.presentation.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellViewModelTest {
    @Test
    fun `initial state comes from injected metadata without hardcoded application name`() {
        val metadata = object : ShellInfo {
            override val applicationName = "Test shell"
        }
        val viewModel = ShellViewModel(metadata)

        assertEquals(ShellUiState("Test shell"), viewModel.state.value)
    }
}
