package com.soundscape.app.navigation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.PlaybackSourceResolver
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.presentation.search.SearchPlayback
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
internal class SearchPlaybackViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val resolver: PlaybackSourceResolver,
) : ViewModel() {
    private val mutable = MutableStateFlow<Pair<UUID, SearchPlayback>?>(null)
    val state = mutable.asStateFlow()
    private var lookup: Job? = null
    private var sourceExpiry: Instant? = null
    private val engine = lazy {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val selection = mutable.value ?: return
                    if (player.currentMediaItem?.mediaId != selection.first.toString()) return
                    val status = when {
                        player.playerError != null -> SearchPlayback.Failed
                        player.isPlaying -> SearchPlayback.Playing
                        player.playWhenReady && player.playbackState != Player.STATE_ENDED -> SearchPlayback.Loading
                        else -> SearchPlayback.Ready
                    }
                    mutable.value = selection.first to status
                }

            })
        }
    }
    internal val player by engine

    fun toggle(hit: SearchHit, openExternal: (URI) -> Unit) {
        val selection = mutable.value?.takeIf { it.first == hit.id }?.second
        when (selection) {
            SearchPlayback.Playing -> player.pause()
            SearchPlayback.Loading -> Unit
            SearchPlayback.Ready -> resume(hit, openExternal)
            else -> resolveAndPlay(hit, openExternal, startAt = 0L)
        }
    }

    private fun resume(hit: SearchHit, openExternal: (URI) -> Unit) {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        if (sourceExpiry?.isAfter(Instant.now()) != false) player.play()
        else resolveAndPlay(hit, openExternal, player.currentPosition)
    }

    private fun resolveAndPlay(hit: SearchHit, openExternal: (URI) -> Unit, startAt: Long) {
        lookup?.cancel()
        mutable.value = hit.id to SearchPlayback.Loading
        if (engine.isInitialized()) {
            player.stop()
            player.clearMediaItems()
        }
        lookup = viewModelScope.launch {
            when (val result = resolver.resolve(hit.id)) {
                is DomainResult.Success -> when (val source = result.value) {
                    is PlaybackSource.InApp -> {
                        sourceExpiry = source.expiresAt
                        player.setMediaItem(MediaItem.Builder().setMediaId(hit.id.toString())
                            .setUri(Uri.parse(source.streamUri.toString())).build(), startAt)
                        player.prepare()
                        player.play()
                    }
                    is PlaybackSource.External -> {
                        if (hit.availability == TrackAvailability.EXTERNAL_ONLY &&
                            runCatching { openExternal(source.uri) }.isSuccess
                        ) mutable.value = null
                        else mutable.value = hit.id to SearchPlayback.Failed
                    }
                }
                is DomainResult.Failure -> mutable.value = hit.id to SearchPlayback.Failed
            }
        }
    }

    fun pause() {
        if (lookup?.isActive == true) {
            lookup?.cancel()
            mutable.value = null
        }
        if (engine.isInitialized()) player.pause()
    }

    override fun onCleared() {
        if (engine.isInitialized()) player.release()
    }
}
