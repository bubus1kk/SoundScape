package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Track
import java.util.UUID

/** Searches and reads recordings by stable catalog ID. */
interface TrackCatalog : TrackSearcher {

    /** Loads one recording using its stable ID. */
    suspend fun getTrack(id: UUID): DomainResult<Track>
}
