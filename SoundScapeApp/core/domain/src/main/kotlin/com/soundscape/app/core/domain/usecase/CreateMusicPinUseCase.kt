package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.util.UUID

class CreateMusicPinUseCase(private val auth: AuthRepository, private val pins: MusicPinRepository) {
    suspend operator fun invoke(id: UUID, input: MusicPinInput): DomainResult<MusicPin> {
        val title = validateTitle(input.title)
        if (title is DomainResult.Failure) return title
        val attachments = validateAttachments(input.trackIds, input.photos, 5)
        if (attachments is DomainResult.Failure) return attachments
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        if ((user as DomainResult.Success).value == null) return DomainResult.Failure(DomainError.Unauthenticated)
        return pins.create(id, input)
    }
}
