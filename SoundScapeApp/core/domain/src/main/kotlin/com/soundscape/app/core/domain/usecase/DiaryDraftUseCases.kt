package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import java.util.UUID

class ObserveDiaryDraftsUseCase(private val repository: DiaryDraftRepository) {
    operator fun invoke(scope: DiaryDraftScope) = repository.observe(scope)
}

class ObserveDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    operator fun invoke(scope: DiaryDraftScope, id: UUID) = repository.observe(scope, id)
}

class SaveDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    suspend operator fun invoke(scope: DiaryDraftScope, id: UUID, input: DiaryDraftInput,
        expectedRevision: Long? = null) = when (val checked = input.validate()) {
        is DomainResult.Failure -> checked
        is DomainResult.Success -> repository.save(scope, id, input, expectedRevision)
    }
}

class DeleteDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    suspend operator fun invoke(scope: DiaryDraftScope, id: UUID, expectedRevision: Long) =
        repository.delete(scope, id, expectedRevision)
}

class ValidateDiaryDraftReadinessUseCase {
    operator fun invoke(input: DiaryDraftInput): List<String> = buildList {
        if (input.title.isBlank() || input.title.length > 150) add("title")
        if (input.body.isBlank()) add("body")
        if (input.eventDate == null) add("eventDate")
        if (input.locationName.isNullOrBlank() && input.coordinates == null && input.musicPinId == null) add("location")
        if (input.trackIds.isEmpty() || input.trackIds.size > 100 || input.trackIds.distinct() != input.trackIds) add("trackIds")
        if (input.photos.size > 10 || input.photos.distinct() != input.photos) add("photos")
        if (input.visibility != com.soundscape.app.core.domain.model.Visibility.PRIVATE) add("visibility")
    }
}
