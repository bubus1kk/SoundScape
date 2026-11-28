package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.CopiedPlaylist
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.repository.SocialRepository
import java.util.UUID

/** Requests an independent copy using a stable, user-owned playlist identity. */
class CopyPublicPlaylistUseCase(private val social: SocialRepository) {
    /** Copies the server-authorized public source without changing the saved links. */
    suspend operator fun invoke(sourceId: UUID, copyId: UUID, idempotencyKey: UUID): DomainResult<CopiedPlaylist> {
        if (sourceId == copyId) return invalid("copyId", "must_differ_from_source")
        return social.copyPlaylist(sourceId, copyId, idempotencyKey)
    }
}
