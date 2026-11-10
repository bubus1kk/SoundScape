package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.util.UUID

class SavePlaylistUseCase(private val auth: AuthRepository, private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: UUID, input: PlaylistInput, expectedRevision: Long? = null): DomainResult<Playlist> {
        val title = validateTitle(input.title)
        if (title is DomainResult.Failure) return title
        if (input.description != null && input.description.length > 2000) return invalid("description", "max_2000")
        if (input.trackIds.size !in 1..100) return invalid("trackIds", "count_1_to_100")
        val genres = validateIds(input.genreIds, "genreIds", 30)
        if (genres is DomainResult.Failure) return genres
        val tags = validateIds(input.tagIds, "tagIds", 30)
        if (tags is DomainResult.Failure) return tags
        if (expectedRevision != null && expectedRevision < 1) return invalid("expectedRevision", "positive")
        val user = auth.currentUser()
        if (user is DomainResult.Failure) return user
        val profile = (user as DomainResult.Success).value ?: return DomainResult.Failure(DomainError.Unauthenticated)
        if (expectedRevision == null) return playlists.create(id, input)
        val existing = playlists.get(id)
        if (existing is DomainResult.Failure) return existing
        val playlist = (existing as DomainResult.Success).value
        if (playlist.ownerId != profile.id || playlist.isEditorial) return DomainResult.Failure(DomainError.Forbidden)
        return playlists.update(id, expectedRevision, input)
    }
}
