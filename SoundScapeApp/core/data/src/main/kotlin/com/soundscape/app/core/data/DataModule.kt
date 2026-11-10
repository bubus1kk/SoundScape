package com.soundscape.app.core.data

import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun visible(reader: RemoteVisiblePlacesReader): VisiblePlacesReader
    @Binds abstract fun search(searcher: RemoteTrackSearcher): TrackSearcher
}
