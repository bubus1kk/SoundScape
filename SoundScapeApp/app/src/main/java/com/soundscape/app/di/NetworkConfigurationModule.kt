package com.soundscape.app.di

import com.soundscape.app.BuildConfig
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.usecase.GetVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.SearchMusicUseCase
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.repository.TrackSearchCache
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.ObserveTrackSearchUseCase
import com.soundscape.app.core.domain.usecase.RefreshTrackSearchUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object NetworkConfigurationModule {
    @Provides fun config(): NetworkConfig = NetworkConfig(
        BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, BuildConfig.DEBUG)
    @Provides fun tokens(): AccessTokenProvider = AccessTokenProvider { null }
    @Provides fun visible(reader: VisiblePlacesReader): GetVisiblePlacesUseCase = GetVisiblePlacesUseCase(reader)
    @Provides fun search(searcher: TrackSearcher): SearchMusicUseCase = SearchMusicUseCase(searcher)
    @Provides fun observePlaces(cache: VisiblePlacesCache) = ObserveVisiblePlacesUseCase(cache)
    @Provides fun refreshPlaces(cache: VisiblePlacesCache) = RefreshVisiblePlacesUseCase(cache)
    @Provides fun observeSearch(cache: TrackSearchCache) = ObserveTrackSearchUseCase(cache)
    @Provides fun refreshSearch(cache: TrackSearchCache) = RefreshTrackSearchUseCase(cache)
}
