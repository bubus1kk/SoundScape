package com.soundscape.app.core.domain.model

import java.time.LocalDate
import java.util.UUID

/** Diary filters used in lists and selection of entries to render on the map. */
data class DiaryQuery(
    val page: PageRequest = PageRequest(),
    val from: LocalDate? = null,
    val until: LocalDate? = null,
    val locationQuery: String? = null,
    val mapOnly: Boolean = false,
)

/** Social state supported by the server. */
enum class SocialKind { PLAYLIST_LIKE, PIN_LIKE, FAVORITE, SAVED_PLAYLIST }

/** Recording or content ID with the corresponding interaction. */
data class SocialTarget(val kind: SocialKind, val id: UUID)

/** Current account's interaction and a public count when applicable. */
data class SocialState(val active: Boolean, val count: Long? = null)

/** Catalog projection used to list playlists without loading their track contents. */
data class PlaylistSummary(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val visibility: Visibility,
    val revision: Long,
    val isEditorial: Boolean,
    val description: String? = null,
    val coverPath: String? = null,
    val placeId: UUID? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
)

/** A saved reference can disappear when the source stops being public. */
data class SavedPlaylistLink(val source: PlaylistSummary)

/** A copy owns a new playlist identity independent of the public source. */
data class CopiedPlaylist(val sourceId: UUID, val playlist: Playlist)

/** Summary included in a user's server-filtered public profile collection. */
data class PublicCollectionItem(val target: ShareTarget, val title: String)
