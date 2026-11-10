package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import java.util.UUID

interface DiaryRepository {
    suspend fun get(id: UUID): DomainResult<DiaryEntry>
    suspend fun create(id: UUID, input: DiaryInput): DomainResult<DiaryEntry>
    suspend fun update(id: UUID, expectedRevision: Long, input: DiaryInput): DomainResult<DiaryEntry>
}
