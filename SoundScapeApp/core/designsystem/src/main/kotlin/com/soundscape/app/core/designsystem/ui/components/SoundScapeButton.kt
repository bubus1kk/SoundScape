package com.soundscape.app.core.designsystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.R
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme

enum class SoundScapeButtonVariant { Primary, Secondary, Outlined, Destructive }

@Composable
fun SoundScapeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SoundScapeButtonVariant = SoundScapeButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = stringResource(R.string.soundscape_loading),
    compact: Boolean = false,
    shape: Shape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.medium,
    outlineColor: Color = SoundScapeTheme.colors.primary,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val container = when (variant) {
        SoundScapeButtonVariant.Primary -> colors.primary
        SoundScapeButtonVariant.Secondary -> colors.primarySoft
        SoundScapeButtonVariant.Outlined -> colors.surface
        SoundScapeButtonVariant.Destructive -> colors.danger
    }
    val content = when (variant) {
        SoundScapeButtonVariant.Primary -> colors.onPrimary
        SoundScapeButtonVariant.Destructive -> MaterialTheme.colorScheme.onError
        else -> colors.primary
    }
    val loadingDescription = stringResource(R.string.soundscape_loading)
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = if (enabled && loading) container else colors.surfaceAlt,
        disabledContentColor = if (enabled && loading) content else colors.textMuted,
    )
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = if (compact) spacing.space32 else spacing.space48).semantics {
            if (loading) {
                stateDescription = loadingDescription
                liveRegion = LiveRegionMode.Polite
            }
        },
        shape = shape,
        colors = buttonColors,
        elevation = null,
        border = if (variant == SoundScapeButtonVariant.Outlined) {
            BorderStroke(1.dp, if (enabled) outlineColor else colors.outline)
        } else null,
        contentPadding = PaddingValues(horizontal = spacing.space16,
            vertical = if (compact) spacing.space4 else spacing.space12),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Text(if (loading) loadingText else text,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
        }
    }
}
