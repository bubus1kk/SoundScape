package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MAX_CONTENT_TRACKS
import com.soundscape.app.core.domain.model.MAX_DIARY_PHOTOS
import com.soundscape.app.core.domain.model.MAX_TITLE_LENGTH
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import java.util.UUID

/** Observes all diary drafts in a local scope. */
class ObserveDiaryDraftsUseCase(private val repository: DiaryDraftRepository) {
    /** Returns the observable list for the selected scope. */
    operator fun invoke(scope: DiaryDraftScope) = repository.observe(scope)
}

/** Observes a single diary draft. */
class ObserveDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    /** Returns updates for the identified draft in its local scope. */
    operator fun invoke(scope: DiaryDraftScope, id: UUID) = repository.observe(scope, id)
}

/** Validates and persists an in-progress diary draft. */
class SaveDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    /** Saves the draft and supplies its expected revision on an edit. */
    suspend operator fun invoke(
        scope: DiaryDraftScope,
        id: UUID,
        input: DiaryDraftInput,
        expectedRevision: Long? = null,
    ) = when (val checked = input.validate()) {
        is DomainResult.Failure -> checked
        is DomainResult.Success -> repository.save(scope, id, input, expectedRevision)
    }
}

/** Discards an existing local diary draft. */
class DeleteDiaryDraftUseCase(private val repository: DiaryDraftRepository) {
    /** Deletes the draft at the expected revision. */
    suspend operator fun invoke(scope: DiaryDraftScope, id: UUID, expectedRevision: Long) =
        repository.delete(scope, id, expectedRevision)
}

/** Identifies fields that prevent a diary draft from becoming a complete entry. */
class ValidateDiaryDraftReadinessUseCase {
    /** Returns unresolved fields in entry form order. */
    operator fun invoke(input: DiaryDraftInput): List<String> = buildList {
        if (input.title.isBlank() || input.title.length > MAX_TITLE_LENGTH) add("title")
        if (input.body.isBlank()) add("body")
        if (input.eventDate == null) add("eventDate")
        if (input.locationName.isNullOrBlank() && input.coordinates == null && input.musicPinId == null) add("location")
        val tracks = input.trackIds
        if (tracks.size !in 1..MAX_CONTENT_TRACKS || tracks.distinct() != tracks) add("trackIds")
        if (input.photos.size > MAX_DIARY_PHOTOS || input.photos.distinct() != input.photos) add("photos")
        if (input.visibility != Visibility.PRIVATE) add("visibility")
    }
}
