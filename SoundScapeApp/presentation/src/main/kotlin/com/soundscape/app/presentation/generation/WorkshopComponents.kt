package com.soundscape.app.presentation.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError

@Composable
internal fun WorkshopField(value: String, change: (String) -> Unit, label: String, max: Int,
    enabled: Boolean, lines: Int = 1, placeholder: String? = null, error: Boolean = false) {
    val colors = SoundScapeTheme.colors
    OutlinedTextField(value, { change(it.take(max)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, isError = error, singleLine = lines == 1, minLines = lines, maxLines = lines + 2,
        placeholder = placeholder?.let { hint -> { Text(hint) } }, shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.background,
            unfocusedContainerColor = colors.background, unfocusedBorderColor = colors.outline))
}

@Composable
internal fun WorkshopCard(content: @Composable ColumnScope.() -> Unit) {
    val spacing = SoundScapeTheme.spacing
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SoundScapeTheme.colors.surface,
    ) {
        Column(
            Modifier.padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
            content = content,
        )
    }
}

@Composable
internal fun WorkshopHeading(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(description, color = SoundScapeTheme.colors.textMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun WorkshopBadge(icon: Int) {
    val colors = SoundScapeTheme.colors
    Box(Modifier.size(SoundScapeTheme.spacing.space48).background(colors.primarySoft, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center) { WorkshopIcon(icon) }
}

@Composable
internal fun WorkshopIcon(icon: Int, label: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), label, modifier.size(20.dp), tint = SoundScapeTheme.colors.primary)
}

@Composable
internal fun WorkshopBanner(title: String, body: String, error: Boolean = false) {
    val colors = SoundScapeTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = if (error) MaterialTheme.colorScheme.errorContainer else colors.primarySoft) {
        Column(Modifier.padding(SoundScapeTheme.spacing.space12)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun WorkshopMessage(title: String, body: String, icon: Int, action: String? = null, click: () -> Unit = {}) {
    WorkshopCard {
        Column(
            Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space24),
        ) {
            WorkshopBadge(icon)
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Text(body, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = SoundScapeTheme.colors.textMuted)
            if (action != null) SoundScapeButton(action, click, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun WorkshopFooter(label: String, enabled: Boolean, busy: Boolean, busyLabel: String,
    icon: Int? = null, click: () -> Unit) {
    Surface(color = SoundScapeTheme.colors.surface) {
        SoundScapeButton(label, click, Modifier.fillMaxWidth().padding(horizontal = SoundScapeTheme.spacing.space16,
            vertical = SoundScapeTheme.spacing.space12), enabled = enabled, loading = busy, loadingText = busyLabel,
            leadingIcon = icon?.let { { Icon(painterResource(it), null, Modifier.size(20.dp)) } })
    }
}

internal fun DomainError.message() = when (this) {
    DomainError.Unauthenticated -> "Требуется повторный вход"
    DomainError.Forbidden -> "Действие недоступно для этого аккаунта"
    DomainError.AlreadyExists, DomainError.VersionConflict -> "Плейлист уже существует или был изменён"
    DomainError.Network, DomainError.Timeout -> "Не удалось связаться с сервером"
    is DomainError.RateLimited -> "Лимит запросов исчерпан"
    is DomainError.Validation -> if (reason == "no_candidates") "Не удалось подобрать композиции"
        else "Сервер отклонил параметры запроса"
    DomainError.Unexpected -> "Сервер вернул некорректный ответ"
    else -> "Сервис временно недоступен"
}
