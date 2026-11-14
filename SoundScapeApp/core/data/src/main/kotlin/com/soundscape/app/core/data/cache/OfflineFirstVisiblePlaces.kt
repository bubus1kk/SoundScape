package com.soundscape.app.core.data.cache

import com.soundscape.app.core.data.RemoteVisiblePlacesReader
import com.soundscape.app.core.database.CacheKeys
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.ConnectivityChecker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.flowOf

@Singleton
class OfflineFirstVisiblePlaces @Inject constructor(
    private val remote: RemoteVisiblePlacesReader, storage: RoomMapStorage,
    connectivity: ConnectivityChecker, tokens: AccessTokenProvider,
) : VisiblePlacesReader, VisiblePlacesCache {
    private val coordinator = PageCoordinator(storage, connectivity::isOffline) {
        tokens.currentAccessToken().isNullOrBlank()
    }
    private fun address(bounds: GeoBounds, page: PageRequest) = PageAddress("guest",
        CacheKeys.map(bounds.southWest.latitude, bounds.southWest.longitude,
        bounds.northEast.latitude, bounds.northEast.longitude), page)

    override suspend fun getVisible(bounds: GeoBounds, page: PageRequest) =
        validateMap(page)?.let { DomainResult.Failure(it) }
        ?: coordinator.get(address(bounds, page)) { remote.getVisible(bounds, page) }

    override fun observe(bounds: GeoBounds, page: PageRequest) = validateMap(page)?.let {
        flowOf(CachedPageState<MapPlace>(refreshError = it))
    } ?: coordinator.observe(address(bounds, page))

    override suspend fun refresh(bounds: GeoBounds, page: PageRequest) =
        validateMap(page)?.let { DomainResult.Failure(it) }
        ?: coordinator.refresh(address(bounds, page)) { remote.getVisible(bounds, page) }
}
