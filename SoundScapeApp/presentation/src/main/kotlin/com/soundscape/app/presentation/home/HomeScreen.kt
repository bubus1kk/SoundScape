package com.soundscape.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.presentation.R

@Composable
fun HomeScreen(
    state: HomeUiState,
    retry: () -> Unit,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    cityPhoto: @Composable (UUID, Modifier) -> Unit = { _, _ -> },
) {
    LazyColumn(modifier.testTag("home-feed"), state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "header") { HomeHeader(onAction) }
        item(key = "cities") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_world), style = MaterialTheme.typography.titleLarge)
                HomeCities(state, retry, onAction, cityPhoto)
            }
        }
        item(key = "ai") { HomeAiCard(onAction) }
    }
}

@Composable
private fun HomeHeader(onAction: (HomeAction) -> Unit) {
    val colors = SoundScapeTheme.colors
    val profile = stringResource(R.string.home_profile)
    val search = stringResource(R.string.home_search)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(onClick = { onAction(HomeAction.Profile) }, shape = CircleShape, color = colors.primarySoft) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_user), profile, Modifier.size(20.dp), tint = colors.primary)
            }
        }
        Surface(onClick = { onAction(HomeAction.Search) }, shape = RoundedCornerShape(22.dp), color = colors.surfaceAlt,
            modifier = Modifier.weight(1f)) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = colors.textMuted)
                Text(search, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeAiCard(onAction: (HomeAction) -> Unit) {
    val colors = SoundScapeTheme.colors
    val title = stringResource(R.string.home_ai)
    Surface(color = colors.surfaceAlt, contentColor = colors.text, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().heightIn(min = 116.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = colors.primarySoft, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_sparkles), null, Modifier.size(22.dp), tint = colors.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.home_ai_description), style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted)
                }
            }
            SoundScapeButton(stringResource(R.string.home_create), { onAction(HomeAction.CreatePlaylist) },
                modifier = Modifier.widthIn(min = 158.dp), compact = true)
        }
    }
}
