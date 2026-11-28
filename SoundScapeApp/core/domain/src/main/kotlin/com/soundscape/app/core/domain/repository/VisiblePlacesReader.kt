package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest

/** Reads published places and pins for a map viewport. */
interface VisiblePlacesReader {
    /** Loads the requested page within geographic bounds. */
    suspend fun getVisible(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>>
}
