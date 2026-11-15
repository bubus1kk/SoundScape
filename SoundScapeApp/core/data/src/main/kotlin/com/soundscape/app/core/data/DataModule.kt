package com.soundscape.app.core.data

import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.data.cache.OfflineFirstVisiblePlaces
import com.soundscape.app.core.data.cache.OfflineFirstTrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.repository.TrackSearchCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun visible(reader: OfflineFirstVisiblePlaces): VisiblePlacesReader
    @Binds abstract fun search(searcher: OfflineFirstTrackSearcher): TrackSearcher
    @Binds abstract fun mapCache(reader: OfflineFirstVisiblePlaces): VisiblePlacesCache
    @Binds abstract fun trackCache(reader: OfflineFirstTrackSearcher): TrackSearchCache
}
