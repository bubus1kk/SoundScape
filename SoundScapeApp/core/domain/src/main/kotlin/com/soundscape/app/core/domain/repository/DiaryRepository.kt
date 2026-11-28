package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DiaryEntry
import com.soundscape.app.core.domain.model.DiaryInput
import com.soundscape.app.core.domain.model.DomainResult
import java.util.UUID

/** Reads and writes user diary entries. */
interface DiaryRepository {
    /** Reads an entry visible to the current user. */
    suspend fun get(id: UUID): DomainResult<DiaryEntry>
    /** Saves a new entry using the supplied stable ID. */
    suspend fun create(id: UUID, input: DiaryInput): DomainResult<DiaryEntry>
    /** Updates an entry at its expected revision. */
    suspend fun update(id: UUID, expectedRevision: Long, input: DiaryInput): DomainResult<DiaryEntry>
}
