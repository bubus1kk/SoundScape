package com.soundscape.app.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.presentation.R

@Composable
internal fun HomeCities(
    state: HomeUiState,
    retry: () -> Unit,
    onAction: (HomeAction) -> Unit,
    cityPhoto: @Composable (UUID, Modifier) -> Unit,
) {
    when (state) {
        HomeUiState.Loading -> Surface(color = SoundScapeTheme.colors.surfaceAlt,
            shape = MaterialTheme.shapes.small) {
            Box(Modifier.fillMaxWidth().height(174.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp).testTag("home-loading"), strokeWidth = 2.dp)
            }
        }
        is HomeUiState.Content -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.fromCache || state.refreshError != null) Text(
                stringResource(contentNoticeText(state.refreshError)),
                style = MaterialTheme.typography.bodySmall, color = SoundScapeTheme.colors.textMuted)
            LazyRow(
                modifier = Modifier.testTag("home-city-list"),
                state = rememberLazyListState(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.cities, key = { it.id.toString() }) { city ->
                    Column(Modifier.width(222.dp).clickable(role = Role.Button) {
                        onAction(HomeAction.City(city.id, city.playlistId))
                    },
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = SoundScapeTheme.colors.surfaceAlt, shape = MaterialTheme.shapes.small) {
                            Box(
                                Modifier.fillMaxWidth().height(174.dp).testTag("home-city-image-${city.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_map_pin), null, Modifier.size(24.dp),
                                    tint = SoundScapeTheme.colors.textMuted)
                                if (!city.coverPath.isNullOrBlank()) {
                                    key(city.coverPath) {
                                        cityPhoto(city.id, Modifier.matchParentSize())
                                    }
                                }
                            }
                        }
                        Text(city.title, style = MaterialTheme.typography.labelLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SoundScapeButton(stringResource(R.string.home_update), retry, compact = true, loading = state.refreshing)
        }
        is HomeUiState.Empty -> CityNotice(R.string.home_empty,
            if (state.fromCache) R.string.home_cached_empty_description else R.string.home_empty_description,
            R.drawable.ic_map_pin, state.refreshError, state.refreshing, retry)
        is HomeUiState.Error -> CityNotice(R.string.home_error, R.string.home_error_description,
            if (state.error == DomainError.Network) R.drawable.ic_offline else R.drawable.ic_alert,
            state.error, false, retry)
    }
}

@Composable
private fun CityNotice(title: Int, description: Int, icon: Int, error: DomainError?,
    refreshing: Boolean, retry: () -> Unit) {
    val colors = SoundScapeTheme.colors
    Surface(color = colors.surfaceAlt, contentColor = colors.text, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().heightIn(min = 174.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = colors.primarySoft, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colors.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted)
                }
            }
            if (error != null) Text(stringResource(errorText(error)),
                style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            SoundScapeButton(stringResource(R.string.settings_retry), retry, compact = true, loading = refreshing)
        }
    }
}

internal fun errorText(error: DomainError) = when (error) {
    DomainError.Network -> R.string.home_offline
    DomainError.Timeout -> R.string.home_timeout
    is DomainError.RateLimited -> R.string.home_rate_limited
    else -> R.string.home_refresh_error
}

internal fun contentNoticeText(error: DomainError?) = when (error) {
    null -> R.string.home_cached
    DomainError.Network -> R.string.home_cache_offline
    DomainError.Timeout -> R.string.home_cache_timeout
    is DomainError.RateLimited -> R.string.home_cache_rate_limited
    else -> R.string.home_cache_error
}
