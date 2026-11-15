package com.soundscape.app.core.data.diary

import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import com.soundscape.app.core.domain.usecase.DeleteDiaryDraftUseCase
import com.soundscape.app.core.domain.usecase.ObserveDiaryDraftUseCase
import com.soundscape.app.core.domain.usecase.ObserveDiaryDraftsUseCase
import com.soundscape.app.core.domain.usecase.SaveDiaryDraftUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
abstract class DiaryDraftModule {
    @Binds abstract fun repository(repository: RoomDiaryDraftRepository): DiaryDraftRepository

    companion object {
        @Provides fun clock(): Clock = Clock.systemUTC()
        @Provides fun observe(repository: DiaryDraftRepository) = ObserveDiaryDraftsUseCase(repository)
        @Provides fun observeOne(repository: DiaryDraftRepository) = ObserveDiaryDraftUseCase(repository)
        @Provides fun save(repository: DiaryDraftRepository) = SaveDiaryDraftUseCase(repository)
        @Provides fun delete(repository: DiaryDraftRepository) = DeleteDiaryDraftUseCase(repository)
    }
}
