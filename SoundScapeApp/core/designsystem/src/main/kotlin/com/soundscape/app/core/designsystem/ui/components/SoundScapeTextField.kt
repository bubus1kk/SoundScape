package com.soundscape.app.core.designsystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.R
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme

@Composable
fun SoundScapeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val focused by interactionSource.collectIsFocusedAsState()
    val showError = enabled && isError
    val message = if (showError) errorText ?: supportingText else supportingText
    val errorDescription = errorText ?: supportingText ?: stringResource(R.string.soundscape_input_error)
    val borderWidth = when {
        !enabled -> 1.dp
        showError -> 1.5.dp
        focused -> 2.dp
        else -> 1.dp
    }
    val borderColor = when {
        !enabled -> colors.outline
        showError -> colors.danger
        focused -> colors.primary
        else -> colors.controlOutline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) colors.text else colors.textMuted,
            ),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (!showError && supportingText != null) {
                    "$label. $supportingText"
                } else label
                if (showError) error(errorDescription)
            },
            decorationBox = { innerTextField ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (enabled) colors.surface else colors.surfaceAlt,
                    border = BorderStroke(borderWidth, borderColor),
                ) {
                    Column(
                        Modifier.heightIn(min = 76.dp)
                            .padding(horizontal = spacing.space16, vertical = spacing.space12),
                        verticalArrangement = Arrangement.spacedBy(spacing.space4),
                    ) {
                        Text(
                            label,
                            Modifier.clearAndSetSemantics { },
                            color = if (showError) colors.danger else colors.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Box {
                            if (value.isEmpty() && placeholder != null) {
                                Text(
                                    placeholder,
                                    Modifier.clearAndSetSemantics { },
                                    color = colors.textMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            innerTextField()
                        }
                    }
                }
            },
        )
        if (message != null) {
            Text(
                message,
                Modifier.padding(horizontal = spacing.space16).clearAndSetSemantics { },
                color = if (showError) colors.danger else colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
