package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DiaryInput
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.model.JourneyInput
import com.soundscape.app.core.domain.model.MAX_CONTENT_TRACKS
import com.soundscape.app.core.domain.model.MAX_DIARY_PHOTOS
import com.soundscape.app.core.domain.model.MAX_JOURNEY_STOPS
import com.soundscape.app.core.domain.model.MAX_PIN_PHOTOS
import com.soundscape.app.core.domain.model.MAX_PLAYLIST_DESCRIPTION
import com.soundscape.app.core.domain.model.MAX_PLAYLIST_FACETS
import com.soundscape.app.core.domain.model.MIN_JOURNEY_STOPS
import com.soundscape.app.core.domain.model.MusicPinInput
import com.soundscape.app.core.domain.model.flatMap
import com.soundscape.app.core.domain.model.MutationIdentity
import com.soundscape.app.core.domain.model.OfflineEdit
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.ShareKind
import com.soundscape.app.core.domain.model.SyncOperation
import com.soundscape.app.core.domain.model.validateAttachments
import com.soundscape.app.core.domain.model.validateTitle
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.MusicDiary
import com.soundscape.app.core.domain.repository.MusicJourneys
import com.soundscape.app.core.domain.repository.MusicPins
import com.soundscape.app.core.domain.repository.PlaylistLibrary

/** Applies one validated revision-aware content operation. Used by immediate saves and the outbox. */
class ApplyContentMutationUseCase(
    private val playlists: PlaylistLibrary,
    private val pins: MusicPins,
    private val diary: MusicDiary,
    private val journeys: MusicJourneys,
) {
    /** Dispatches the original typed payload, stable object ID and idempotency key together. */
    suspend operator fun invoke(operation: SyncOperation): DomainResult<Unit> {
        val identity = operation.identity
        return when (val edit = operation.edit) {
            is OfflineEdit.Playlist -> validatePlaylist(edit.input).flatMap {
                playlists.save(identity, edit.input).discardValue()
            }
            is OfflineEdit.Pin -> validatePin(edit.input).flatMap { pins.save(identity, edit.input).discardValue() }
            is OfflineEdit.Diary -> validateDiary(edit.input).flatMap {
                val input = if (identity.expectedRevision == null && edit.input.visibility == null) {
                    edit.input.copy(visibility = Visibility.PRIVATE)
                } else edit.input
                diary.save(identity, input).discardValue()
            }
            is OfflineEdit.Journey -> validateJourney(edit.input).flatMap {
                journeys.save(identity, edit.input).discardValue()
            }
            is OfflineEdit.Delete -> delete(edit.kind, identity)
        }
    }

    private suspend fun delete(kind: ShareKind, identity: MutationIdentity): DomainResult<Unit> = when (kind) {
        ShareKind.PLAYLIST -> playlists.delete(identity)
        ShareKind.MUSIC_PIN -> pins.delete(identity)
        ShareKind.TRAVEL_EVENT -> diary.delete(identity)
        ShareKind.JOURNEY -> journeys.delete(identity)
        ShareKind.PROFILE -> invalid("kind", "unsupported_mutation")
    }
}

private fun DomainResult<*>.discardValue(): DomainResult<Unit> = when (this) {
    is DomainResult.Failure -> this
    is DomainResult.Success -> DomainResult.Success(Unit)
}

internal fun validatePlaylist(input: PlaylistInput): DomainResult<Unit> = validateTitle(input.title).flatMap {
    when {
        (input.description?.length ?: 0) > MAX_PLAYLIST_DESCRIPTION -> invalid("description", "max_2000")
        input.trackIds.size !in 1..MAX_CONTENT_TRACKS -> invalid("trackIds", "count_1_to_100")
        else -> validateIds(input.genreIds, "genreIds", MAX_PLAYLIST_FACETS).flatMap {
            validateIds(input.tagIds, "tagIds", MAX_PLAYLIST_FACETS)
        }
    }
}

internal fun validatePin(input: MusicPinInput): DomainResult<Unit> =
    validateTitle(input.title).flatMap { validateAttachments(input.trackIds, input.photos, MAX_PIN_PHOTOS) }

internal fun validateDiary(input: DiaryInput): DomainResult<Unit> = validateTitle(input.title).flatMap {
    when {
        input.locationName != null && input.locationName.isBlank() -> invalid("locationName", "blank")
        input.locationName == null && input.coordinates == null && input.musicPinId == null ->
            invalid("location", "required")
        else -> validateAttachments(input.trackIds, input.photos, MAX_DIARY_PHOTOS)
    }
}

internal fun validateJourney(input: JourneyInput): DomainResult<Unit> =
    validateTitle(input.title).flatMap {
        if (input.stops.size !in MIN_JOURNEY_STOPS..MAX_JOURNEY_STOPS) invalid("stops", "count_2_to_50")
        else DomainResult.Success(Unit)
    }
