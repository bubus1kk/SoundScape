package com.soundscape.app.presentation.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePalettes
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.presentation.R


private const val CHEVRON_REVERSE_DEGREES = 180f
private val atlasDisabledFooterColor = Color(0xFF9BABCE)

private val generatorFooterShape = RoundedCornerShape(15.dp)
@Composable
internal fun GeneratorTopBar(back: () -> Unit, showMark: Boolean) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(color = colors.surface, contentColor = colors.text) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = spacing.space48 + spacing.space8)
                .padding(horizontal = spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(back) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.profile_back),
                    Modifier.size(spacing.space24).rotate(CHEVRON_REVERSE_DEGREES))
            }
            Text(stringResource(R.string.generator_title), Modifier.weight(1f).padding(vertical = spacing.space8),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.size(spacing.space48), contentAlignment = Alignment.Center) {
                if (showMark) Icon(painterResource(R.drawable.ic_sparkles), null,
                    Modifier.size(spacing.space24), tint = colors.primary)
            }
        }
    }
}

@Composable
internal fun GeneratorBadge(icon: Int, size: androidx.compose.ui.unit.Dp) {
    val colors = SoundScapeTheme.colors
    Box(Modifier.size(size).background(colors.primarySoft, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center) { WorkshopIcon(icon) }
}

@Composable
internal fun GeneratorNotice(title: String, body: String, loading: Boolean) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val container = when {
        loading -> colors.primarySoft
        colors.background == SoundScapePalettes.AtlasLight.background -> Color(0xFFFBE9ED)
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container) {
        Row(
            Modifier.heightIn(min = spacing.space48 + spacing.space40).padding(spacing.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            if (loading)
                CircularProgressIndicator(
                    Modifier.size(spacing.space20),
                    color = colors.primary,
                    strokeWidth = 2.dp,
                )
            else
                Icon(
                    painterResource(R.drawable.ic_alert),
                    null,
                    Modifier.size(spacing.space20),
                    tint = colors.danger,
                )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.space8),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = colors.text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}

@Composable
internal fun GeneratorBlocked(title: String, body: String, icon: Int, action: String?, click: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = spacing.space20, vertical = spacing.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space24)) {
            Box(Modifier.size(spacing.space48 + spacing.space24)
                .background(colors.primarySoft, MaterialTheme.shapes.large), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(spacing.space40), tint = colors.primary)
            }
            Text(
                title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            Text(
                body,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            if (action != null) SoundScapeButton(action, click, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun GeneratorStop(index: Int, count: Int, title: String, enabled: Boolean, reordering: Boolean,
    move: (Int) -> Unit, remove: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentMove by rememberUpdatedState(move)
    val drag = if (enabled && count > 1) Modifier.pointerInput(Unit) {
        var displacement = 0f
        detectDragGesturesAfterLongPress(onDragStart = { displacement = 0f }) { change, delta ->
            change.consume()
            displacement += delta.y
            val next = when {
                displacement > size.height * 0.75f -> currentIndex + 1
                displacement < -size.height * 0.75f -> currentIndex - 1
                else -> currentIndex
            }
            if (next != currentIndex && next in 0 until currentCount) {
                currentMove(next)
                displacement = 0f
            }
        }
    } else Modifier
    Surface(
        Modifier.fillMaxWidth().then(drag),
        shape = MaterialTheme.shapes.medium,
        color = colors.background,
    ) {
        Row(
            Modifier.heightIn(min = spacing.space48 + spacing.space12).padding(start = spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space8),
        ) {
            Box(
                Modifier.size(spacing.space32).background(colors.primarySoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text((index + 1).toString(), color = colors.primary, style = MaterialTheme.typography.labelMedium)
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = colors.text)
                Text(when (index) {
                    0 -> "Начало маршрута"
                    count - 1 -> "Последняя точка"
                    else -> "Промежуточная точка"
                }, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            GeneratorStopActions(index, count, title, enabled, reordering, move, remove)
        }
    }
}

@Composable
private fun GeneratorStopActions(
    index: Int,
    count: Int,
    title: String,
    enabled: Boolean,
    reordering: Boolean,
    move: (Int) -> Unit,
    remove: () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    if (reordering) {
        IconButton(
            { move(index - 1) },
            enabled = enabled && index > 0,
            modifier = Modifier.size(spacing.space40),
        ) {
            WorkshopIcon(R.drawable.ic_chevron_down, "Выше: $title", Modifier.rotate(CHEVRON_REVERSE_DEGREES))
        }
        IconButton(
            { move(index + 1) },
            enabled = enabled && index < count - 1,
            modifier = Modifier.size(spacing.space40),
        ) {
            WorkshopIcon(R.drawable.ic_chevron_down, "Ниже: $title")
        }
    }
    IconButton(remove, enabled = enabled, modifier = Modifier.size(spacing.space40)) {
        Icon(
            painterResource(R.drawable.ic_x),
            "Удалить: $title",
            Modifier.size(spacing.space20),
            tint = colors.textMuted,
        )
    }
    if (!reordering) {
        Box(Modifier.size(spacing.space40), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_grip_vertical),
                null,
                Modifier.size(spacing.space16),
                tint = colors.textMuted,
            )
        }
    }
}

@Composable
internal fun GeneratorFooter(label: String, enabled: Boolean, busy: Boolean, click: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val atlas = colors.background == SoundScapePalettes.AtlasLight.background
    Surface(color = colors.surface) {
        Column {
            HorizontalDivider(color = colors.surfaceAlt)
            Button(click, Modifier.fillMaxWidth().padding(horizontal = spacing.space16, vertical = spacing.space12)
                .heightIn(min = spacing.space48), enabled = enabled && !busy,
                shape = generatorFooterShape, contentPadding = PaddingValues(horizontal = spacing.space16),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
                    disabledContainerColor = if (atlas) atlasDisabledFooterColor else colors.surfaceAlt,
                    disabledContentColor = if (atlas) colors.onPrimary else colors.textMuted)) {
                Box(Modifier.fillMaxWidth().padding(start = spacing.space24)) {
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterStart).size(spacing.space20),
                        color = LocalContentColor.current, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_sparkles), null,
                        Modifier.align(Alignment.CenterStart).size(spacing.space20))
                    Text(if (busy) "Генерация…" else label, Modifier.align(Alignment.Center)
                        .padding(start = spacing.space24), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
