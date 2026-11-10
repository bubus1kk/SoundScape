package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.net.URI
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*

internal val id = UUID(0, 1)
internal val owner = UUID(0, 2)
internal val otherId = UUID(0, 3)
internal val point = (GeoPoint.create(10.0, 170.0) as DomainResult.Success).value
internal val bounds = (GeoBounds.create(point, (GeoPoint.create(20.0, -170.0) as DomainResult.Success).value) as DomainResult.Success).value
internal val profile = UserProfile(owner, "Listener")
internal fun track(trackId: UUID = id, availability: TrackAvailability = TrackAvailability.IN_APP) = Track(trackId, "Track", "Artist", 30.seconds, availability)
internal val mapPlace = MapPlace(id, MapPlaceKind.CITY, "City", point)
internal fun playlist() = Playlist(id, owner, "List", Visibility.PUBLIC, listOf(PlaylistItem(track())), 2)
internal fun pin() = MusicPin(id, owner, "Pin", point, listOf(id), emptyList(), Visibility.PUBLIC, 1)
internal fun entry() = DiaryEntry(id, owner, "Entry", LocalDate.of(2026, 11, 10), listOf(id), emptyList(), 2, Visibility.PUBLIC, locationName = "City")
internal val stops = listOf(JourneyStop(id, JourneyStopKind.PLACE), JourneyStop(otherId, JourneyStopKind.PIN))
internal fun journey() = MusicJourney(id, owner, "Route", Visibility.PRIVATE, stops, 1)
internal fun playlistInput() = PlaylistInput("List", Visibility.PUBLIC, listOf(id, otherId, id), "Description", "opaque", listOf(1), listOf(2))
internal fun pinInput() = MusicPinInput("Pin", point, listOf(id), Visibility.UNLISTED)
internal fun diaryInput() = DiaryInput("Entry", LocalDate.of(2026, 11, 10), listOf(id), locationName = "City")
internal fun journeyInput() = JourneyInput("Route", Visibility.PRIVATE, stops)
internal fun photos(count: Int) = List(count) { MediaReference("photo-$it") }
internal val internalSource = PlaybackSource.InApp(id, URI("https://example.invalid/stream"))
internal val externalSource = PlaybackSource.External(id, URI("https://example.invalid/track"))
internal val draft = GeneratedPlaylistDraft("Draft", "Description", listOf(GeneratedItem(otherId), GeneratedItem(id)), listOf("warning"))
internal val failures = listOf(DomainError.Network, DomainError.Timeout, DomainError.RateLimited(30.seconds), DomainError.Server, DomainError.Forbidden)
internal fun failure(error: DomainError) = DomainResult.Failure(error)
internal fun assertError(result: DomainResult<*>, error: DomainError) = assertEquals(failure(error), result)
internal fun assertValidation(result: DomainResult<*>) { assertTrue(result is DomainResult.Failure && result.error is DomainError.Validation) }
internal fun assertSuccess(result: DomainResult<*>) = assertTrue(result.toString(), result is DomainResult.Success)
internal suspend fun assertCancellation(block: suspend () -> Unit) {
    try { block(); fail("Cancellation must propagate") }
    catch (error: CancellationException) { assertEquals("cancelled", error.message) }
}
internal suspend fun cancel(): Unit = throw CancellationException("cancelled")

internal class FakeAuth : AuthRepository {
    var current: DomainResult<UserProfile?> = DomainResult.Success(profile)
    var signedIn: DomainResult<UserProfile> = DomainResult.Success(profile)
    var calls = 0
    var credentials: Pair<String, String>? = null
    var before: suspend () -> Unit = {}
    override suspend fun currentUser(): DomainResult<UserProfile?> {
        calls++
        before()
        return current
    }
    override suspend fun signIn(email: String, password: String): DomainResult<UserProfile> {
        calls++; credentials = email to password; before(); return signedIn
    }
}
internal class FakePlaces : MusicPlaceRepository {
    var result: DomainResult<Page<MapPlace>> = DomainResult.Success(Page(listOf(mapPlace), false))
    var linked: DomainResult<List<Track>> = DomainResult.Success(listOf(track()))
    var calls = 0
    var linkedCalls = 0
    var receivedBounds: GeoBounds? = null
    var page: PageRequest? = null
    var center: GeoPoint? = null
    var radius: Int? = null
    var linkedTarget: Pair<UUID, MapPlaceKind>? = null
    var before: suspend () -> Unit = {}
    override suspend fun getVisible(bounds: GeoBounds, page: PageRequest): DomainResult<Page<MapPlace>> {
        calls++; receivedBounds = bounds; this.page = page; before(); return result
    }
    override suspend fun getNearby(center: GeoPoint, radiusMeters: Int, page: PageRequest): DomainResult<Page<MapPlace>> {
        calls++; this.center = center; radius = radiusMeters; this.page = page; before(); return result
    }
    override suspend fun getDiscoveryCandidates(page: PageRequest): DomainResult<Page<MapPlace>> {
        calls++; this.page = page; before(); return result
    }
    override suspend fun getDiscoveryTracks(placeId: UUID, kind: MapPlaceKind): DomainResult<List<Track>> {
        linkedCalls++; linkedTarget = placeId to kind; before(); return linked
    }
}
internal class FakeCatalog : TrackCatalog {
    var result: DomainResult<Page<Track>> = DomainResult.Success(Page(listOf(track()), false))
    var calls = 0
    var input: MusicSearch? = null
    var before: suspend () -> Unit = {}
    override suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>> {
        calls++
        input = parameters
        before()
        return result
    }
    override suspend fun getTrack(id: UUID): DomainResult<Track> = error("Unexpected track prefetch")
}
internal class FakeLocation : LocationProvider {
    var result: DomainResult<GeoPoint> = DomainResult.Success(point)
    var calls = 0
    var before: suspend () -> Unit = {}
    override suspend fun currentLocation(): DomainResult<GeoPoint> {
        calls++
        before()
        return result
    }
}
internal class FakePlaylists : PlaylistRepository {
    var result: DomainResult<Playlist> = DomainResult.Success(playlist())
    var existing: DomainResult<Playlist> = DomainResult.Success(playlist())
    var creates = 0
    var updates = 0
    var reads = 0
    var input: PlaylistInput? = null
    var savedId: UUID? = null
    var revision: Long? = null
    var beforeWrite: suspend () -> Unit = {}
    override suspend fun get(id: UUID): DomainResult<Playlist> {
        reads++
        return existing
    }
    override suspend fun create(id: UUID, input: PlaylistInput): DomainResult<Playlist> {
        creates++
        savedId = id
        this.input = input
        beforeWrite()
        return result
    }
    override suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput): DomainResult<Playlist> {
        updates++; savedId = id; revision = expectedRevision; this.input = input; beforeWrite(); return result
    }
}
internal class FakePins : MusicPinRepository {
    var result: DomainResult<MusicPin> = DomainResult.Success(pin())
    var calls = 0
    var input: MusicPinInput? = null
    var savedId: UUID? = null
    var before: suspend () -> Unit = {}
    override suspend fun create(id: UUID, input: MusicPinInput): DomainResult<MusicPin> {
        calls++
        savedId = id
        this.input = input
        before()
        return result
    }
}
internal class FakeDiary : DiaryRepository {
    var result: DomainResult<DiaryEntry> = DomainResult.Success(entry())
    var existing: DomainResult<DiaryEntry> = DomainResult.Success(entry())
    var creates = 0
    var updates = 0
    var reads = 0
    var input: DiaryInput? = null
    var savedId: UUID? = null
    var revision: Long? = null
    var beforeWrite: suspend () -> Unit = {}
    override suspend fun get(id: UUID): DomainResult<DiaryEntry> {
        reads++
        return existing
    }
    override suspend fun create(id: UUID, input: DiaryInput): DomainResult<DiaryEntry> {
        creates++
        savedId = id
        this.input = input
        beforeWrite()
        return result
    }
    override suspend fun update(id: UUID, expectedRevision: Long, input: DiaryInput): DomainResult<DiaryEntry> {
        updates++; savedId = id; revision = expectedRevision; this.input = input; beforeWrite(); return result
    }
}
internal class FakeJourneys : JourneyRepository {
    var result: DomainResult<MusicJourney> = DomainResult.Success(journey())
    var calls = 0
    var input: JourneyInput? = null
    var before: suspend () -> Unit = {}
    override suspend fun create(id: UUID, input: JourneyInput): DomainResult<MusicJourney> {
        calls++
        this.input = input
        before()
        return result
    }
}
internal class FakeResolver : PlaybackSourceResolver {
    var result: DomainResult<PlaybackSource> = DomainResult.Success(internalSource)
    var calls = 0
    var before: suspend () -> Unit = {}
    override suspend fun resolve(trackId: UUID): DomainResult<PlaybackSource> {
        calls++
        before()
        return result
    }
}
internal class FakePlayer : PlaybackController {
    var result: DomainResult<Unit> = DomainResult.Success(Unit)
    var calls = 0
    var source: PlaybackSource.InApp? = null
    var before: suspend () -> Unit = {}
    override suspend fun play(source: PlaybackSource.InApp): DomainResult<Unit> {
        calls++
        this.source = source
        before()
        return result
    }
}
internal class FakeGenerator : PlaylistGenerator {
    var result: DomainResult<GeneratedPlaylistDraft> = DomainResult.Success(draft)
    var calls = 0
    var input: GenerationRequest? = null
    var before: suspend () -> Unit = {}
    override suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> {
        calls++
        input = request
        before()
        return result
    }
}
internal class FakeRandom(vararg indices: Int) : RandomSource {
    val values = indices.toList()
    val bounds = mutableListOf<Int>()
    var result: DomainResult.Failure? = null
    override fun nextIndex(upperBoundExclusive: Int): DomainResult<Int> {
        bounds.add(upperBoundExclusive); return result ?: DomainResult.Success(values.getOrElse(bounds.lastIndex) { 0 })
    }
}
internal class FakeSync : SyncRequester {
    var result: DomainResult<SyncRequestStatus> = DomainResult.Success(SyncRequestStatus.ACCEPTED)
    var calls = 0
    var before: suspend () -> Unit = {}
    override suspend fun requestSync(): DomainResult<SyncRequestStatus> {
        calls++
        before()
        return result
    }
}
