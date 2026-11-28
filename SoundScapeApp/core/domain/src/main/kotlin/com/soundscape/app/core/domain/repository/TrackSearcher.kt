package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.Track

/** Searches recordings using catalog filters and pagination. */
interface TrackSearcher {
    /** Returns the requested track search page. */
    suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>>
}
