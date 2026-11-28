package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicPin
import com.soundscape.app.core.domain.model.MusicPinInput
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.MusicPinRepository
import java.util.UUID

/** Creates a music pin from an authenticated user's validated input. */
class CreateMusicPinUseCase(private val auth: AuthRepository, private val pins: MusicPinRepository) {
    /** Saves the pin using its stable identity. */
    suspend operator fun invoke(id: UUID, input: MusicPinInput): DomainResult<MusicPin> =
        validatePin(input)
            .flatMap { auth.requireCurrentUser() }
            .flatMap { pins.create(id, input) }
}
