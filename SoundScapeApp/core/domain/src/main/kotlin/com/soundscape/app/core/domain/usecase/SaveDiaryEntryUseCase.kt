package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.util.UUID

class SaveDiaryEntryUseCase(private val auth: AuthRepository, private val diary: DiaryRepository) {
    suspend operator fun invoke(id: UUID, input: DiaryInput, expectedRevision: Long? = null): DomainResult<DiaryEntry> {
        val title = validateTitle(input.title)
        if (title is DomainResult.Failure) return title
        if (input.locationName != null && input.locationName.isBlank()) return invalid("locationName", "blank")
        if (input.locationName == null && input.coordinates == null && input.musicPinId == null) return invalid("location", "required")
        val attachments = validateAttachments(input.trackIds, input.photos, 10)
        if (attachments is DomainResult.Failure) return attachments
        if (expectedRevision != null && expectedRevision < 1) return invalid("expectedRevision", "positive")
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        val profile = (user as DomainResult.Success).value ?: return DomainResult.Failure(DomainError.Unauthenticated)
        if (expectedRevision == null) {
            return diary.create(id, input.copy(visibility = input.visibility ?: Visibility.PRIVATE))
        }
        val existing = diary.get(id)
        if (existing is DomainResult.Failure) return existing
        val entry = (existing as DomainResult.Success).value
        if (entry.ownerId != profile.id) return DomainResult.Failure(DomainError.Forbidden)
        return diary.update(id, expectedRevision, input.copy(visibility = input.visibility ?: entry.visibility))
    }
}
