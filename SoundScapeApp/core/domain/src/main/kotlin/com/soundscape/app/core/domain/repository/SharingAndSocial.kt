package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.CopiedPlaylist
import com.soundscape.app.core.domain.model.CreatedShare
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.OpenedShare
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.PublicCollectionItem
import com.soundscape.app.core.domain.model.SavedPlaylistLink
import com.soundscape.app.core.domain.model.ShareKind
import com.soundscape.app.core.domain.model.ShareLookup
import com.soundscape.app.core.domain.model.ShareTarget
import com.soundscape.app.core.domain.model.SocialState
import com.soundscape.app.core.domain.model.SocialTarget
import com.soundscape.app.core.domain.model.Track
import java.time.Instant
import java.util.UUID

/** Authorized server-side link management and object resolution. */
interface ShareRepository {
    /** Creates a link with optional expiry. */
    suspend fun create(target: ShareTarget, expiresAt: Instant? = null): DomainResult<CreatedShare>

    /** Revokes a previously issued link. */
    suspend fun revoke(shareId: UUID): DomainResult<Unit>

    /** Opens a public or token-authorized object. */
    suspend fun open(lookup: ShareLookup): DomainResult<OpenedShare>
}

/** Social actions and the account's collections. */
interface SocialRepository {
    /** Reads current social state. */
    suspend fun read(target: SocialTarget): DomainResult<SocialState>

    /** Sets the desired state, allowing a safe repeat of the same intent. */
    suspend fun set(target: SocialTarget, active: Boolean): DomainResult<SocialState>

    /** Loads favorited tracks. */
    suspend fun favorites(page: PageRequest): DomainResult<Page<Track>>

    /** Loads references to public playlists saved by this account. */
    suspend fun savedPlaylists(page: PageRequest): DomainResult<Page<SavedPlaylistLink>>

    /** Copies a public playlist under a newly requested stable identity. */
    suspend fun copyPlaylist(sourceId: UUID, copyId: UUID, idempotencyKey: UUID): DomainResult<CopiedPlaylist>

    /** Reads collection items that the server exposes on a public profile. */
    suspend fun publicCollection(
        profileId: UUID,
        kind: ShareKind,
        page: PageRequest,
    ): DomainResult<Page<PublicCollectionItem>>
}
