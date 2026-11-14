package com.soundscape.app.core.designsystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme

@Composable
fun SoundScapeFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val content = when {
        !enabled -> colors.textMuted
        selected -> colors.primary
        else -> colors.text
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides spacing.space48) {
        Surface(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.semantics { role = Role.Button },
            shape = MaterialTheme.shapes.medium,
            color = when {
                !enabled -> colors.surfaceAlt
                selected -> colors.primarySoft
                else -> colors.surface
            },
            contentColor = content,
            border = if (!selected || !enabled) BorderStroke(1.dp, if (enabled) colors.controlOutline else colors.outline) else null,
        ) {
            Row(
                Modifier.heightIn(min = 36.dp)
                    .padding(horizontal = spacing.space12, vertical = spacing.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            ) {
                if (selected) {
                    Canvas(Modifier.size(spacing.space24)) {
                        val stroke = 1.8.dp.toPx()
                        drawLine(content, Offset(size.width * .2f, size.height * .5f),
                            Offset(size.width * .4f, size.height * .7f), stroke, StrokeCap.Round)
                        drawLine(content, Offset(size.width * .4f, size.height * .7f),
                            Offset(size.width * .8f, size.height * .25f), stroke, StrokeCap.Round)
                    }
                }
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
