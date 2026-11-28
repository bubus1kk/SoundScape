package com.soundscape.app.core.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Incomplete editor inputs can exist locally before they are valid for sync. */
sealed interface LocalContentInput {
    /** Editable playlist content saved locally. */
    data class Playlist(val input: PlaylistInput) : LocalContentInput
    /** Editable journey content saved locally. */
    data class Journey(val input: JourneyInput) : LocalContentInput

    /** Editable pin content that may still lack coordinates or attachments. */
    data class Pin(
        val title: String = "",
        val coordinates: GeoPoint? = null,
        val trackIds: List<UUID> = emptyList(),
        val visibility: Visibility = Visibility.PRIVATE,
        val photos: List<MediaReference> = emptyList(),
        val description: String? = null,
        val visitDate: LocalDate? = null,
    ) : LocalContentInput
}

/** Local drafts keep the target ID through editing, submission and retry. */
data class LocalContentDraft(
    val id: UUID,
    val scope: LocalAccountScope,
    val input: LocalContentInput,
    val updatedAt: Instant,
    val revision: Long,
    val basedOnServerRevision: Long? = null,
)
