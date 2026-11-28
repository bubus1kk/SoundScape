package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Exposes cached map pages and their refresh operation. */
interface VisiblePlacesCache {
    /** Observes cached data and load state for a map viewport. */
    fun observe(bounds: GeoBounds, page: PageRequest = PageRequest()): Flow<CachedPageState<MapPlace>>
    /** Refreshes the requested viewport page. */
    suspend fun refresh(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Unit>
}

/** Exposes cached track search pages and their refresh operation. */
interface TrackSearchCache {
    /** Observes cached data and load state for the supplied search. */
    fun observe(parameters: MusicSearch): Flow<CachedPageState<Track>>
    /** Refreshes the cached result for the supplied search. */
    suspend fun refresh(parameters: MusicSearch): DomainResult<Unit>
}
