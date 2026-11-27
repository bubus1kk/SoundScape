package com.soundscape.app.core.designsystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    externalLabelFieldHeight: Dp? = null,
    labelHint: String? = null,
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
        else -> if (externalLabelFieldHeight != null) colors.outline else colors.controlOutline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
        if (externalLabelFieldHeight != null) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.text)
                labelHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
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
                    shape = if (externalLabelFieldHeight != null) MaterialTheme.shapes.small
                    else MaterialTheme.shapes.medium,
                    color = if (enabled) colors.surface else colors.surfaceAlt,
                    border = BorderStroke(borderWidth, borderColor),
                ) {
                    Column(
                        Modifier.heightIn(min = externalLabelFieldHeight ?: 76.dp).padding(
                            horizontal = if (externalLabelFieldHeight == null) spacing.space16 else spacing.space12,
                            vertical = spacing.space12,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.space4),
                    ) {
                        if (externalLabelFieldHeight == null) Text(
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
                Modifier.padding(horizontal = if (externalLabelFieldHeight == null) spacing.space16
                    else spacing.space0).clearAndSetSemantics { },
                color = if (showError) colors.danger else colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
