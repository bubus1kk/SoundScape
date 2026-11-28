package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DomainFlowsTest {
    private val now = Instant.parse("2026-11-28T09:00:00Z")

    @Test fun `share creation validates expiry and resolves tokens only on the server`() = runTest {
        val target = ShareTarget(ShareKind.MUSIC_PIN, id)
        val opened = OpenedShare(target, "Pin", "/share/MUSIC_PIN/$id")
        val expected = CreatedShare(ShareMetadata(otherId, target, now.plusSeconds(60)), opened.routePath)
        val calls = mutableListOf<Any>()
        val repository = object : ShareRepository {
            override suspend fun create(target: ShareTarget, expiresAt: Instant?): DomainResult<CreatedShare> {
                calls.add(target)
                return DomainResult.Success(expected)
            }
            override suspend fun revoke(shareId: UUID): DomainResult<Unit> {
                calls.add(shareId)
                return DomainResult.Success(Unit)
            }
            override suspend fun open(lookup: ShareLookup): DomainResult<OpenedShare> {
                calls.add(lookup)
                return if (lookup is ShareLookup.Token) DomainResult.Failure(DomainError.ExpiredShare)
                else DomainResult.Success(opened)
            }
        }
        val shares = ManageShareUseCase(repository, Clock.fixed(now, ZoneOffset.UTC))
        assertValidation(shares.create(target.copy(kind = ShareKind.PROFILE)))
        assertValidation(shares.create(target, now))
        assertError(shares.open(ShareLookup.Token("bad")), DomainError.InvalidShare)
        assertEquals(emptyList<Any>(), calls)
        assertEquals(DomainResult.Success(expected), shares.create(target, expected.metadata.expiresAt))
        assertSuccess(shares.revoke(otherId))
        val token = ShareLookup.Token("A".repeat(32))
        val byId = ShareLookup.Target(target)
        assertError(shares.open(token), DomainError.ExpiredShare)
        assertEquals(DomainResult.Success(opened), shares.open(byId))
        assertEquals(listOf(target, otherId, token, byId), calls)
    }

    @Test fun `search keeps place and pin distinct and forwards strict filter and paging choices`() = runTest {
        val pin = SearchHit(SearchCategory.PLACE, id, "Pin", null, null, null, SearchPlaceKind.PIN)
        val place = pin.copy(placeKind = SearchPlaceKind.PLACE)
        val requests = mutableListOf<Triple<GlobalSearch, Int, Int>>()
        val repository = object : GlobalSearchRepository {
            override val history = MutableStateFlow(emptyList<String>())
            override suspend fun rememberQuery(query: String) = Unit
            override suspend fun clearHistory() = Unit
            override suspend fun options(facet: SearchFacet) = DomainResult.Success(emptyList<SearchOption>())
            override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
                requests.add(Triple(request, limit, offset))
                if (offset == 1000) return DomainResult.Failure(DomainError.SourceUnavailable())
                return DomainResult.Success(Page(if (request.includePublicMusicPins) listOf(place, pin) else listOf(place), false))
            }
        }
        val useCase = SearchCatalogUseCase(repository)
        val filters = SearchFilters(genreIds = listOf(1, 2), moodId = 3, onlyInApp = true)
        val request = GlobalSearch("  City ", SearchCategory.PLACE, filters, includePublicMusicPins = true)
        assertValidation(useCase(request.copy(query = " ")))
        assertValidation(useCase(request, PageRequest(offset = 1001)))
        assertValidation(useCase(request, PageRequest(limit = 51)))
        assertValidation(useCase(request.copy(filters = filters.copy(genreIds = listOf(0)))))
        assertValidation(useCase(request.copy(filters = filters.copy(moodIds = listOf(1, 1)))))
        assertTrue(requests.isEmpty())
        assertEquals(DomainResult.Success(Page(listOf(place, pin), false)), useCase(request, PageRequest(50, 3)))
        assertEquals(Triple(request.copy(query = "City"), 50, 3), requests.single())
        assertError(useCase(request, PageRequest(50, 1000)), DomainError.SourceUnavailable())
        assertEquals(DomainResult.Success(Page(listOf(place), false)), useCase(request.copy(includePublicMusicPins = false)))
    }

    @Test fun `editor approval applies only to the reviewed revision`() = runTest {
        val version = EditorialRevision(id, 3)
        val content = EditorialContent("City", "Details", "cover", listOf(1), listOf(2), emptyList())
        val draft = EditorialDraft(version, otherId, content, EditorialApproval(2, otherId, now), 2)
        val calls = mutableListOf<Pair<String, EditorialRevision>>()
        val port = object : EditorialPublication {
            override suspend fun approve(expected: EditorialRevision): DomainResult<EditorialDraft> {
                calls.add("approve" to expected)
                return DomainResult.Success(draft.copy(approval = EditorialApproval(expected.revision, otherId, now)))
            }
            override suspend fun publish(expected: EditorialRevision): DomainResult<EditorialDraft> {
                calls.add("publish" to expected)
                return DomainResult.Failure(DomainError.RevisionMismatch(3, 4))
            }
            override suspend fun unpublish(expected: EditorialRevision): DomainResult<EditorialDraft> {
                calls.add("withdraw" to expected)
                return DomainResult.Success(draft.copy(publishedRevision = null))
            }
        }
        val useCase = PublishEditorialUseCase(port)
        assertValidation(useCase.publish(draft))
        assertValidation(useCase.approve(version.copy(revision = 0)))
        assertValidation(useCase.withdraw(version.copy(revision = 0)))
        assertTrue(calls.isEmpty())
        val approved = (useCase.approve(version) as DomainResult.Success).value
        assertEquals(2, approved.publishedRevision)
        assertError(useCase.publish(approved), DomainError.RevisionMismatch(3, 4))
        assertSuccess(useCase.withdraw(version))
        assertEquals(listOf("approve" to version, "publish" to version, "withdraw" to version), calls)
    }

    @Test fun `shake can be disabled and manual discovery works without a sensor`() = runTest {
        val places = FakePlaces()
        val discover = DiscoverRandomPlaceTrackUseCase(places, FakeRandom())
        val useCase = ShakeDiscoveryUseCase(discover)
        assertEquals(
            DomainResult.Success(null),
            useCase.onShake(DiscoveryPreferences(shakeEnabled = false), DeviceAccess.AVAILABLE),
        )
        assertError(
            useCase.onShake(DiscoveryPreferences(), DeviceAccess.UNAVAILABLE),
            DomainError.ShakeUnavailable(DeviceAccess.UNAVAILABLE),
        )
        assertSuccess(useCase.randomPlace())
        assertSuccess(useCase.onShake(DiscoveryPreferences(), DeviceAccess.AVAILABLE))
    }

    @Test fun `copy has its own identity and a saved reference keeps the original identity`() = runTest {
        val saved = SavedPlaylistLink(PlaylistSummary(id, otherId, "Original", Visibility.PUBLIC, 5, false))
        val copy = CopiedPlaylist(id, playlist().copy(id = otherId, ownerId = profile.id, revision = 1))
        val calls = mutableListOf<Triple<UUID, UUID, UUID>>()
        val port = object : SocialRepository {
            override suspend fun read(target: SocialTarget) = DomainResult.Success(SocialState(false))
            override suspend fun set(target: SocialTarget, active: Boolean) = DomainResult.Success(SocialState(active))
            override suspend fun favorites(page: PageRequest) = DomainResult.Success(Page(emptyList<Track>(), false))
            override suspend fun savedPlaylists(page: PageRequest) = DomainResult.Success(Page(listOf(saved), false))
            override suspend fun publicCollection(profileId: UUID, kind: ShareKind, page: PageRequest) =
                DomainResult.Success(Page(emptyList<PublicCollectionItem>(), false))
            override suspend fun copyPlaylist(sourceId: UUID, copyId: UUID, idempotencyKey: UUID): DomainResult<CopiedPlaylist> {
                calls.add(Triple(sourceId, copyId, idempotencyKey))
                return DomainResult.Success(copy)
            }
        }
        val useCase = CopyPublicPlaylistUseCase(port)
        val key = UUID(1, 1)
        assertValidation(useCase(id, id, key))
        assertEquals(DomainResult.Success(copy), useCase(id, otherId, key))
        assertEquals(listOf(Triple(id, otherId, key)), calls)
        assertEquals(id, saved.source.id)
        assertEquals(otherId, copy.playlist.id)
    }

    @Test fun `geofences clear on denial and opt out while preserving chosen radius`() = runTest {
        val updates = mutableListOf<List<MusicGeofence>>()
        val useCase = UpdateGeofencesUseCase { regions ->
            updates.add(regions)
            DomainResult.Success(Unit)
        }
        val place = MusicPlace(id, "Place", GeoPoint.create(50.0, 10.0).let { (it as DomainResult.Success).value })
        val selected = DiscoveryPreferences(geofencingEnabled = true, geofenceRadius = GeofenceRadius.M500)
        assertSuccess(useCase(DiscoveryPreferences(), DeviceAccess.AVAILABLE, listOf(place)))
        assertSuccess(useCase(selected, DeviceAccess.DENIED, listOf(place)))
        assertSuccess(useCase(selected, DeviceAccess.AVAILABLE, listOf(place, place)))
        assertEquals(listOf(emptyList(), emptyList(), listOf(MusicGeofence(place, GeofenceRadius.M500))), updates)
    }
}
