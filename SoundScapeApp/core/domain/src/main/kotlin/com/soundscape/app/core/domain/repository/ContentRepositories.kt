package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DiaryEntry
import com.soundscape.app.core.domain.model.DiaryInput
import com.soundscape.app.core.domain.model.DiaryQuery
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.JourneyInput
import com.soundscape.app.core.domain.model.MusicJourney
import com.soundscape.app.core.domain.model.MusicPin
import com.soundscape.app.core.domain.model.MusicPinInput
import com.soundscape.app.core.domain.model.MusicPlace
import com.soundscape.app.core.domain.model.MutationIdentity
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.PlaylistSummary
import java.util.UUID

/** List scopes supported by the catalog. */
enum class ContentScope { PUBLIC, MINE }

/** Place cards and their published official playlist references. */
interface PlaceCards {
    /** Reads a published place. Its playlist ID can be loaded with PlaylistReader. */
    suspend fun getPlace(id: UUID): DomainResult<MusicPlace>
}

/** Full revision-aware playlist access used by the library and outbox. */
interface PlaylistLibrary : PlaylistRepository {
    /** Lists visible library items for the selected scope. */
    suspend fun list(scope: ContentScope, page: PageRequest): DomainResult<Page<PlaylistSummary>>

    /** Saves the ordered playlist within the session bound to mutation.accountId. */
    suspend fun save(mutation: MutationIdentity, input: PlaylistInput): DomainResult<Playlist>

    /** Deletes the playlist with the matching revision. */
    suspend fun delete(mutation: MutationIdentity): DomainResult<Unit>
}

/** Full music pin access, including the user's list and server-visible detail. */
interface MusicPins : MusicPinRepository {
    /** Reads a pin allowed in the current access context. */
    suspend fun get(id: UUID): DomainResult<MusicPin>

    /** Lists this account's marks. */
    suspend fun listMine(page: PageRequest): DomainResult<Page<MusicPin>>

    /** Creates or updates a mark within the session bound to mutation.accountId. */
    suspend fun save(mutation: MutationIdentity, input: MusicPinInput): DomainResult<MusicPin>

    /** Deletes a mark with the matching revision. */
    suspend fun delete(mutation: MutationIdentity): DomainResult<Unit>
}

/** Full diary access including dated lists and offline mutation support. */
interface MusicDiary : DiaryRepository {
    /** Lists the diary in descending date order using the supplied filters. */
    suspend fun list(query: DiaryQuery): DomainResult<Page<DiaryEntry>>

    /** Creates or updates a diary entry within the session bound to mutation.accountId. */
    suspend fun save(mutation: MutationIdentity, input: DiaryInput): DomainResult<DiaryEntry>

    /** Deletes a diary entry with the matching revision. */
    suspend fun delete(mutation: MutationIdentity): DomainResult<Unit>
}

/** Full journey access with ordered stops and server-resolved route availability. */
interface MusicJourneys : JourneyRepository {
    /** Reads the journey in the current access context. */
    suspend fun get(id: UUID): DomainResult<MusicJourney>

    /** Lists public or owned journeys. */
    suspend fun list(scope: ContentScope, page: PageRequest): DomainResult<Page<MusicJourney>>

    /** Creates or updates a journey within the session bound to mutation.accountId. */
    suspend fun save(mutation: MutationIdentity, input: JourneyInput): DomainResult<MusicJourney>

    /** Deletes a journey with the matching revision. */
    suspend fun delete(mutation: MutationIdentity): DomainResult<Unit>
}
