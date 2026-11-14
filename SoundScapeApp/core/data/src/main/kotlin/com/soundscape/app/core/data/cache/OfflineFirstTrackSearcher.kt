package com.soundscape.app.core.data.cache

import com.soundscape.app.core.data.RemoteTrackSearcher
import com.soundscape.app.core.database.CacheKeys
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.ConnectivityChecker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.flowOf

@Singleton
class OfflineFirstTrackSearcher @Inject constructor(
    private val remote: RemoteTrackSearcher, storage: RoomTrackStorage,
    connectivity: ConnectivityChecker, tokens: AccessTokenProvider,
) : TrackSearcher, TrackSearchCache {
    private val coordinator = PageCoordinator(storage, connectivity::isOffline) {
        tokens.currentAccessToken().isNullOrBlank()
    }
    private fun address(parameters: MusicSearch) = PageAddress("guest",
        CacheKeys.search(parameters.query, parameters.onlyInApp, parameters.genreIds, parameters.moodIds),
        parameters.page)

    override suspend fun search(parameters: MusicSearch) = validateSearch(parameters)?.let { DomainResult.Failure(it) }
        ?: coordinator.get(address(parameters)) { remote.search(parameters) }

    override fun observe(parameters: MusicSearch) = validateSearch(parameters)?.let {
        flowOf(CachedPageState<Track>(refreshError = it))
    } ?: coordinator.observe(address(parameters))

    override suspend fun refresh(parameters: MusicSearch) = validateSearch(parameters)?.let { DomainResult.Failure(it) }
        ?: coordinator.refresh(address(parameters)) { remote.search(parameters) }
}
