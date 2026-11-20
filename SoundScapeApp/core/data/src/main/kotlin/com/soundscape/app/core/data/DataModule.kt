package com.soundscape.app.core.data

import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.data.cache.OfflineFirstVisiblePlaces
import com.soundscape.app.core.data.cache.OfflineFirstTrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.repository.PlaylistReader
import com.soundscape.app.core.domain.repository.PlaybackSourceResolver
import com.soundscape.app.core.domain.repository.ProfileRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.network.AccessTokenProvider
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
    @Binds abstract fun playlistReader(reader: RemotePlaylistReader): PlaylistReader
    @Binds abstract fun playbackSourceResolver(resolver: RemotePlaybackSourceResolver): PlaybackSourceResolver
    @Binds abstract fun profileRepository(repository: RemoteProfileRepository): ProfileRepository
    @Binds abstract fun session(session: SupabaseProfileSession): ProfileSession
    @Binds abstract fun tokens(session: SupabaseProfileSession): AccessTokenProvider
}
