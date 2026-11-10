package com.soundscape.app.debug

import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.usecase.GetVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.SearchMusicUseCase
import com.soundscape.app.core.network.NetworkConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsViewModelTest {
    @Test fun `opening screen and missing config never requests data`() {
        val reader = object : VisiblePlacesReader {
            override suspend fun getVisible(bounds: GeoBounds, page: PageRequest): DomainResult<Page<MapPlace>> =
                error("must not request")
        }
        val searcher = object : TrackSearcher {
            override suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>> = error("must not request")
        }
        val model = NetworkDiagnosticsViewModel(GetVisiblePlacesUseCase(reader), SearchMusicUseCase(searcher),
            NetworkConfig("", ""))
        assertTrue(model.state.value.startsWith("Ready"))
        model.loadPlaces()
        assertTrue(model.state.value.startsWith("Error: configure"))
        model.searchTracks()
        assertTrue(model.state.value.startsWith("Error: configure"))
    }
}
