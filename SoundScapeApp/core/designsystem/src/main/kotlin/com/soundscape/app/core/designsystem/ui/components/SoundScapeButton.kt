package com.soundscape.app.core.designsystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.R
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme

enum class SoundScapeButtonVariant { Primary, Secondary, Outlined, Destructive }

/** Loading changes the label, not the geometry or the externally owned state. */
@Composable
fun SoundScapeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SoundScapeButtonVariant = SoundScapeButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = stringResource(R.string.soundscape_loading),
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
    // Keep the enabled appearance while loading, but disable interaction through Material.
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = if (enabled && loading) container else colors.surfaceAlt,
        disabledContentColor = if (enabled && loading) content else colors.textMuted,
    )
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = spacing.space48).semantics {
            if (loading) {
                stateDescription = loadingDescription
                liveRegion = LiveRegionMode.Polite
            }
        },
        shape = MaterialTheme.shapes.medium,
        colors = buttonColors,
        elevation = null,
        border = if (variant == SoundScapeButtonVariant.Outlined) {
            BorderStroke(1.dp, if (enabled) colors.primary else colors.outline)
        } else null,
        contentPadding = PaddingValues(horizontal = spacing.space16, vertical = spacing.space12),
    ) {
        Text(if (loading) loadingText else text, style = MaterialTheme.typography.labelLarge)
    }
}
