package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Explicit observation and refresh; observation never starts an HTTP request. */
interface VisiblePlacesCache {
    fun observe(bounds: GeoBounds, page: PageRequest = PageRequest()): Flow<CachedPageState<MapPlace>>
    suspend fun refresh(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Unit>
}

/** Search cache identity preserves the exact server request parameters. */
interface TrackSearchCache {
    fun observe(parameters: MusicSearch): Flow<CachedPageState<Track>>
    suspend fun refresh(parameters: MusicSearch): DomainResult<Unit>
}
