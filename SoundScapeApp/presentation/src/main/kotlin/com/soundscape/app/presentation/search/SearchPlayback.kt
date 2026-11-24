package com.soundscape.app.presentation.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.R

enum class SearchPlayback { Loading, Playing, Ready, Failed }

@Composable
internal fun SearchPlaybackAction(hit: SearchHit, playback: SearchPlayback?, onTrack: (SearchHit) -> Unit) {
    if (hit.category != SearchCategory.TRACK || hit.availability == null) return
    val label = when (playback) {
        SearchPlayback.Playing -> R.string.search_pause_track
        SearchPlayback.Loading -> R.string.search_loading_track
        SearchPlayback.Ready -> R.string.search_resume_track
        SearchPlayback.Failed -> R.string.search_retry_track
        null -> if (hit.availability == TrackAvailability.EXTERNAL_ONLY) R.string.search_open_track
        else R.string.search_play_track
    }
    TextButton({ onTrack(hit) }, enabled = playback != SearchPlayback.Loading) {
        Text(stringResource(label))
    }
}
