package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.LocalAccountScope
import com.soundscape.app.core.domain.model.LocalContentDraft
import com.soundscape.app.core.domain.model.LocalContentInput
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Local persistence for incomplete pin, playlist and journey edits. */
interface LocalContentDrafts {
    /** Observes drafts in the selected local partition. */
    fun observe(scope: LocalAccountScope): Flow<List<LocalContentDraft>>

    /** Saves the local input with a local revision precondition. */
    suspend fun save(
        scope: LocalAccountScope,
        id: UUID,
        input: LocalContentInput,
        expectedRevision: Long?,
        basedOnServerRevision: Long?,
    ): DomainResult<LocalContentDraft>

    /** Discards the requested local revision after explicit user action. */
    suspend fun delete(scope: LocalAccountScope, id: UUID, expectedRevision: Long): DomainResult<Unit>
}
