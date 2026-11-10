package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface VisiblePlacesReader {
    suspend fun getVisible(bounds: GeoBounds, page: PageRequest = PageRequest()): DomainResult<Page<MapPlace>>
}
