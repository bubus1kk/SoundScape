package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface DiaryDraftRepository {
    fun observe(scope: DiaryDraftScope): Flow<DomainResult<List<DiaryDraft>>>
    fun observe(scope: DiaryDraftScope, id: UUID): Flow<DomainResult<DiaryDraft?>>
    suspend fun get(scope: DiaryDraftScope, id: UUID): DomainResult<DiaryDraft?>
    suspend fun save(scope: DiaryDraftScope, id: UUID, input: DiaryDraftInput,
        expectedRevision: Long?): DomainResult<DiaryDraft>
    suspend fun delete(scope: DiaryDraftScope, id: UUID, expectedRevision: Long): DomainResult<Unit>
}
