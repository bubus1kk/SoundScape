package com.soundscape.app.presentation.generation

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.presentation.R

private const val CHEVRON_REVERSE_DEGREES = 180f

@Composable
internal fun OrderedWorkshopRow(index: Int, count: Int, title: String, subtitle: String?, editable: Boolean,
    move: ((Int) -> Unit)?, remove: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentMove by rememberUpdatedState(move)
    val drag = if (editable && move != null) Modifier.pointerInput(Unit) {
        var displacement = 0f
        detectDragGesturesAfterLongPress(onDragStart = { displacement = 0f }) { change, delta ->
            change.consume()
            displacement += delta.y
            val threshold = size.height * 0.65f
            val next = when {
                displacement > threshold -> currentIndex + 1
                displacement < -threshold -> currentIndex - 1
                else -> currentIndex
            }
            if (next != currentIndex && next in 0 until currentCount) {
                currentMove?.invoke(next)
                displacement = 0f
            }
        }
    } else Modifier
    Surface(Modifier.fillMaxWidth().then(drag), shape = MaterialTheme.shapes.small, color = colors.surface) {
        Row(Modifier.padding(start = SoundScapeTheme.spacing.space12).heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = SoundScapeTheme.spacing.space8)) {
                Text("${index + 1}. $title", style = MaterialTheme.typography.titleSmall)
                if (subtitle != null)
                    Text(
                        subtitle,
                        color = colors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            if (editable && move != null) {
                IconButton({ move(index - 1) }, enabled = index > 0) {
                    WorkshopIcon(R.drawable.ic_chevron_down, "Выше: $title", Modifier.rotate(CHEVRON_REVERSE_DEGREES))
                }
                IconButton({ move(index + 1) }, enabled = index + 1 < count) {
                    WorkshopIcon(R.drawable.ic_chevron_down, "Ниже: $title")
                }
            }
            if (editable) IconButton(remove) { WorkshopIcon(R.drawable.ic_trash_2, "Удалить: $title") }
        }
    }
}
