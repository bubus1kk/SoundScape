package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContentActionsTest {
    private val account = MutationIdentity(profile.id, id, UUID(1, 1))
    private val seen = mutableListOf<Pair<MutationIdentity, Any>>()
    private val pins = object : MusicPins, MusicPinRepository by FakePins() {
        var pinResult: DomainResult<MusicPin> = DomainResult.Failure(DomainError.NotFound)
        override suspend fun get(id: UUID) = pinResult
        override suspend fun listMine(page: PageRequest) = emptyPage<MusicPin>()
        override suspend fun save(mutation: MutationIdentity, input: MusicPinInput) = record<MusicPin>(mutation, input)
        override suspend fun delete(mutation: MutationIdentity) = remove(mutation, ShareKind.MUSIC_PIN)
    }
    private val diary = object : MusicDiary, DiaryRepository by FakeDiary() {
        override suspend fun list(query: DiaryQuery) = emptyPage<DiaryEntry>()
        override suspend fun save(mutation: MutationIdentity, input: DiaryInput) = record<DiaryEntry>(mutation, input)
        override suspend fun delete(mutation: MutationIdentity) = remove(mutation, ShareKind.TRAVEL_EVENT)
    }
    private val playlists = object : PlaylistLibrary, PlaylistRepository by FakePlaylists() {
        override suspend fun list(scope: ContentScope, page: PageRequest) = emptyPage<PlaylistSummary>()
        override suspend fun save(mutation: MutationIdentity, input: PlaylistInput) = record<Playlist>(mutation, input)
        override suspend fun delete(mutation: MutationIdentity) = remove(mutation, ShareKind.PLAYLIST)
    }
    private val journeys = object : MusicJourneys, JourneyRepository by FakeJourneys() {
        override suspend fun list(scope: ContentScope, page: PageRequest) = emptyPage<MusicJourney>()
        override suspend fun get(id: UUID) = DomainResult.Failure(DomainError.NotFound)
        override suspend fun save(mutation: MutationIdentity, input: JourneyInput) = record<MusicJourney>(mutation, input)
        override suspend fun delete(mutation: MutationIdentity) = remove(mutation, ShareKind.JOURNEY)
    }
    private val apply = ApplyContentMutationUseCase(playlists, pins, diary, journeys)

    @Test fun `mutation routes keep account identity revision and track ordering`() = runTest {
        val playlist = PlaylistInput("Saved", Visibility.UNLISTED, listOf(id, otherId, id))
        val pin = MusicPinInput("Pin", point, listOf(id), Visibility.PUBLIC)
        val diary = DiaryInput("Memory", LocalDate.of(2026, 11, 28), listOf(id), coordinates = point)
        val journey = JourneyInput("Trip", Visibility.PRIVATE, stops)
        val revision = account.copy(expectedRevision = 4)
        assertValidation(apply(SyncOperation(account, OfflineEdit.Pin(pin.copy(trackIds = emptyList())))))
        assertValidation(apply(SyncOperation(account, OfflineEdit.Diary(diary.copy(coordinates = null)))))
        assertValidation(apply(SyncOperation(account, OfflineEdit.Journey(journey.copy(stops = listOf(stops.first()))))))
        assertTrue(seen.isEmpty())
        assertError(apply(SyncOperation(revision, OfflineEdit.Playlist(playlist))), DomainError.VersionConflict)
        assertError(apply(SyncOperation(revision, OfflineEdit.Pin(pin))), DomainError.VersionConflict)
        assertError(apply(SyncOperation(account, OfflineEdit.Diary(diary))), DomainError.VersionConflict)
        assertError(apply(SyncOperation(revision, OfflineEdit.Diary(diary))), DomainError.VersionConflict)
        assertError(apply(SyncOperation(revision, OfflineEdit.Journey(journey))), DomainError.VersionConflict)
        assertEquals(listOf(
            revision to playlist,
            revision to pin,
            account to diary.copy(visibility = Visibility.PRIVATE),
            revision to diary,
            revision to journey,
        ), seen)
        for (kind in listOf(ShareKind.PLAYLIST, ShareKind.MUSIC_PIN, ShareKind.TRAVEL_EVENT, ShareKind.JOURNEY)) {
            assertSuccess(apply(SyncOperation(revision, OfflineEdit.Delete(kind))))
            assertEquals(revision to kind, seen.last())
        }
        assertValidation(apply(SyncOperation(revision, OfflineEdit.Delete(ShareKind.PROFILE))))
        assertThrows(IllegalArgumentException::class.java) { SyncOperation(account, OfflineEdit.Delete(ShareKind.JOURNEY)) }
    }

    @Test fun `map card follows its kind and published playlist link`() = runTest {
        val reader = FakePlaylists()
        val city = MapPlace(id, MapPlaceKind.CITY, "City", point, playlistId = id)
        val load = LoadPlaceCardUseCase(reader, pins)
        assertEquals(DomainResult.Success(PlaceCard.City(city, playlist())), load(city))
        val withoutPlaylist = city.copy(playlistId = null)
        assertEquals(DomainResult.Success(PlaceCard.City(withoutPlaylist, null)), load(withoutPlaylist))
        reader.existing = DomainResult.Failure(DomainError.NotFound)
        assertEquals(DomainResult.Success(PlaceCard.City(city, null)), load(city))
        reader.existing = DomainResult.Failure(DomainError.Network)
        assertError(load(city), DomainError.Network)
        val pinCard = city.copy(kind = MapPlaceKind.PIN)
        assertError(load(pinCard), DomainError.NotFound)
        val pin = MusicPin(id, profile.id, "Pin", point, listOf(otherId), emptyList(), Visibility.PUBLIC, 1)
        pins.pinResult = DomainResult.Success(pin)
        assertEquals(DomainResult.Success(PlaceCard.Pin(pin)), load(pinCard))
    }

    private fun <T> emptyPage(): DomainResult<Page<T>> = DomainResult.Success(Page(emptyList(), false))

    private fun <T> record(mutation: MutationIdentity, input: Any): DomainResult<T> {
        seen.add(mutation to input)
        return DomainResult.Failure(DomainError.VersionConflict)
    }

    private fun remove(mutation: MutationIdentity, kind: ShareKind): DomainResult<Unit> {
        seen.add(mutation to kind)
        return DomainResult.Success(Unit)
    }
}
