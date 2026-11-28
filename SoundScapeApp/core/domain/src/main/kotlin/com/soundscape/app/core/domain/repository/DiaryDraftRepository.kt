package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Persists incomplete diary drafts in a local scope. */
interface DiaryDraftRepository {
    /** Observes the drafts in the scope. */
    fun observe(scope: DiaryDraftScope): Flow<DomainResult<List<DiaryDraft>>>
    /** Observes a single draft or its absence. */
    fun observe(scope: DiaryDraftScope, id: UUID): Flow<DomainResult<DiaryDraft?>>
    /** Reads a draft by its local identity. */
    suspend fun get(scope: DiaryDraftScope, id: UUID): DomainResult<DiaryDraft?>
    /** Creates or updates a draft using the supplied revision when present. */
    suspend fun save(scope: DiaryDraftScope, id: UUID, input: DiaryDraftInput,
        expectedRevision: Long?): DomainResult<DiaryDraft>
    /** Deletes a draft at the expected revision. */
    suspend fun delete(scope: DiaryDraftScope, id: UUID, expectedRevision: Long): DomainResult<Unit>
}
