package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.repository.AuthRepository
import com.soundscape.app.core.domain.repository.PlaylistRepository
import java.util.UUID

/** Saves a user playlist, retaining the expected revision on edits. */
class SavePlaylistUseCase(private val auth: AuthRepository, private val playlists: PlaylistRepository) {
    /** Validates the ordered track list and delegates the authorized mutation. */
    suspend operator fun invoke(
        id: UUID,
        input: PlaylistInput,
        expectedRevision: Long? = null,
    ): DomainResult<Playlist> =
        validatePlaylist(input)
            .flatMap { validateExpectedRevision(expectedRevision) }
            .flatMap { auth.requireCurrentUser() }
            .flatMap { profile ->
                if (expectedRevision == null) playlists.create(id, input)
                else update(id, input, expectedRevision, profile.id)
            }

    private suspend fun update(id: UUID, input: PlaylistInput, revision: Long, ownerId: UUID): DomainResult<Playlist> =
        playlists.get(id).flatMap { playlist ->
            if (playlist.ownerId != ownerId || playlist.isEditorial) DomainResult.Failure(DomainError.Forbidden)
            else playlists.update(id, revision, input)
        }
}
