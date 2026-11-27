package com.soundscape.app.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.presentation.R

@Composable
internal fun SearchFailure(state: GlobalSearchState, retry: () -> Unit) {
    val offline = state.error == DomainError.Network
    SearchMessage(
        if (offline) stringResource(R.string.search_offline)
        else stringResource(R.string.search_failed),
        when {
            state.waitingForRetry ->
                stringResource(R.string.search_rate_limited)
            offline -> stringResource(R.string.search_offline_hint)
            else -> stringResource(R.string.search_failed_hint)
        },
        stringResource(R.string.search_retry),
        retry,
        !state.waitingForRetry,
        if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
    )
}

@Composable
internal fun SearchMessage(title: String, body: String, action: String? = null, onAction: () -> Unit = {},
    enabled: Boolean = true, icon: Int = R.drawable.ic_search) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(Modifier.padding(top = spacing.space32), shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(spacing.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space20, Alignment.CenterVertically)) {
            Box(Modifier.size(spacing.space48).background(colors.primarySoft, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center) { SearchIcon(icon) }
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                textAlign = TextAlign.Center,
                color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (action != null)
                SoundScapeButton(action, onAction, Modifier.fillMaxWidth(), enabled = enabled)
        }
    }
}
