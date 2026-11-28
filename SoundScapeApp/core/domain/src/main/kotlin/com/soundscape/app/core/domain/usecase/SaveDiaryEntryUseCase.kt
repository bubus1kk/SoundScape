package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DiaryEntry
import com.soundscape.app.core.domain.model.DiaryInput
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.DiaryRepository
import java.util.UUID

/** Saves a diary entry with private visibility by default. */
class SaveDiaryEntryUseCase(private val auth: AuthRepository, private val diary: DiaryRepository) {
    /** Creates or edits an entry using the supplied revision. */
    suspend operator fun invoke(
        id: UUID,
        input: DiaryInput,
        expectedRevision: Long? = null,
    ): DomainResult<DiaryEntry> =
        validateDiary(input)
            .flatMap { validateExpectedRevision(expectedRevision) }
            .flatMap { auth.requireCurrentUser() }
            .flatMap { profile ->
                if (expectedRevision == null) {
                    diary.create(id, input.copy(visibility = input.visibility ?: Visibility.PRIVATE))
                } else update(id, input, expectedRevision, profile.id)
            }

    private suspend fun update(id: UUID, input: DiaryInput, revision: Long, ownerId: UUID): DomainResult<DiaryEntry> =
        diary.get(id).flatMap { entry ->
            if (entry.ownerId != ownerId) DomainResult.Failure(DomainError.Forbidden)
            else diary.update(id, revision, input.copy(visibility = input.visibility ?: entry.visibility))
        }
}
