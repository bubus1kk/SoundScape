package com.soundscape.app.presentation.generation

import androidx.lifecycle.ViewModelStore
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeneratedItem
import com.soundscape.app.core.domain.model.GeneratedPlaylistDraft
import com.soundscape.app.core.domain.model.GenerationRequest
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.PlaylistItem
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.GlobalSearch
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.PlaylistGenerator
import com.soundscape.app.core.domain.repository.PlaylistRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import com.soundscape.app.core.domain.repository.TrackMetadata
import com.soundscape.app.core.domain.repository.TrackMetadataReader
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistWorkshopViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val fixture = Fixture()
    private val saved = MemoryWorkshopState()
    private var time = 100_000L

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun cleanUp() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun formValidatesModesAndRestoresTheOriginalSelection() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        assertNull(model.state.value.form.request())
        model.form { it.copy(mode = GenerationMode.ADVANCED) }
        assertNull(model.state.value.form.request())
        val option = SearchOption(3, "Ambient")
        model.form { it.copy(genre = option, onlyInApp = true, count = 15) }
        val advanced = model.state.value.form.request() as GenerationRequest.Advanced
        assertEquals(listOf(3), advanced.options.genreIds)
        assertTrue(advanced.options.onlyInApp)
        assertEquals(15, advanced.options.count)
        assertNull(advanced.prompt)
        model.form { it.copy(mode = GenerationMode.PLACE) }
        assertNull(model.state.value.form.request())
        val first = hit(SearchCategory.PLACE)
        val second = hit(SearchCategory.PLACE)
        model.form { it.copy(place = first, placePrompt = "  quiet  ") }
        assertEquals(GenerationRequest.Place(first.id, prompt = "quiet"), model.state.value.form.request())
        model.form { it.copy(mode = GenerationMode.JOURNEY, stops = listOf(first)) }
        assertNull(model.state.value.form.request())
        model.form { it.copy(stops = List(MAX_JOURNEY_STOPS + 1) { first }) }
        assertNull(model.state.value.form.request())
        model.form { it.copy(stops = listOf(second, first)) }
        val restored = model("restored")
        val request = restored.state.value.form.request() as GenerationRequest.Journey
        assertEquals(listOf(second.id, first.id), request.stops.map { it.id })
        assertEquals(option, restored.state.value.form.genre)
    }

    @Test fun generationHonorsCooldownAndMaintainsTheDraftOnErrors() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.generate { error("Invalid form") }
        model.form { it.copy(quick = "  quiet music  ") }
        fixture.generateError = DomainError.RateLimited(2.seconds)
        model.generate { error("Throttled") }
        advanceUntilIdle()
        assertEquals(DomainError.RateLimited(2.seconds), model.state.value.generationError)
        assertNull(saved.draft)
        model.dismissGenerationError()
        assertNull(model.state.value.generationError)
        model.generate { error("Still throttled") }
        assertEquals(1, fixture.requests.size)
        assertFalse(model.state.value.generating)
        time += 2_000
        fixture.generateError = null
        var opened = false
        model.generate { opened = true }
        model.form { it.copy(quick = "ignored during generation") }
        model.generate { error("Duplicate generation") }
        advanceUntilIdle()
        assertEquals(GenerationRequest.Quick("quiet music"), fixture.requests.last())
        assertTrue(opened)
        assertEquals(fixture.track.id, saved.draft?.tracks?.single()?.id)
        assertEquals(fixture.track.title, model.state.value.metadata[fixture.track.id]?.title)
        fixture.generateError = DomainError.NoCandidates
        model.generate { error("No candidates") }
        advanceUntilIdle()
        assertEquals(DomainError.NoCandidates, model.state.value.generationError)
        assertEquals(fixture.track.id, saved.draft?.tracks?.single()?.id)
    }

    @Test fun signOutWhileGeneratingDoesNotInstallAnotherAccountsDraft() = runTest(dispatcher) {
        val model = model()
        model.form { it.copy(quick = "ambient") }
        runCurrent()
        val wait = CompletableDeferred<Unit>()
        fixture.generationGate = wait
        model.generate { error("Account changed") }
        runCurrent()
        assertTrue(model.state.value.generating)
        fixture.accountId.value = null
        wait.complete(Unit)
        advanceUntilIdle()
        assertNull(saved.draft)
        assertFalse(model.state.value.generating)
        model.generate { error("Signed out") }
        assertEquals(1, fixture.requests.size)
    }

    @Test fun editsRejectDuplicatesAndWrongCategoryAndRecoverMetadata() = runTest(dispatcher) {
        saved.draft = fixture.draft()
        fixture.metadataError = DomainError.Network
        val model = model()
        advanceUntilIdle()
        assertTrue(model.state.value.metadataError)
        fixture.metadataError = null
        model.catalogActions.refreshMetadata()
        advanceUntilIdle()
        assertFalse(model.state.value.metadataError)
        assertEquals(setOf(fixture.track.id), model.state.value.metadata.keys)
        val extra = hit(SearchCategory.TRACK)
        model.add(hit(SearchCategory.PLACE))
        model.add(extra.copy(id = fixture.track.id))
        model.add(extra)
        advanceUntilIdle()
        model.edit { it.copy(tracks = it.tracks.reordered(1, 0)) }
        assertEquals(listOf(extra.id, fixture.track.id), saved.draft?.input()?.trackIds)
        assertEquals(extra.availability, saved.draft?.tracks?.first()?.availability)
        model.edit { it.copy(tracks = List(MAX_DRAFT_TRACKS) { EditableTrack(UUID.randomUUID()) }) }
        model.add(hit(SearchCategory.TRACK))
        assertEquals(MAX_DRAFT_TRACKS, saved.draft?.tracks?.size)
        model.discard()
        assertNull(saved.draft)
        assertTrue(model.state.value.metadata.isEmpty())
    }

    @Test fun uncertainSaveRestoresAndReconcilesWithoutDuplicateCreate() = runTest(dispatcher) {
        val draft = fixture.draft().copy(title = "  New playlist  ", visibility = Visibility.UNLISTED)
        saved.draft = draft
        fixture.createError = DomainError.Network
        fixture.writeDespiteError = true
        val model = model()
        runCurrent()
        model.save()
        model.save()
        advanceUntilIdle()
        assertEquals(1, fixture.writes.size)
        assertTrue(requireNotNull(saved.draft).uncertain)
        model.edit { error("Uncertain drafts are locked") }
        model.discard()
        model.generate { error("Cannot replace an uncertain draft") }
        assertEquals(draft.id, saved.draft?.id)

        val restored = model("restored")
        advanceUntilIdle()
        restored.save()
        advanceUntilIdle()
        assertEquals(1, fixture.writes.size)
        assertEquals(listOf(draft.id), fixture.reads)
        val confirmed = requireNotNull(restored.state.value.draft)
        assertTrue(confirmed.confirmed)
        assertFalse(confirmed.uncertain)
        assertEquals("New playlist", confirmed.title)
        assertEquals("reason", confirmed.tracks.single().reason)
        assertEquals(1L, confirmed.revision)
        assertNull(restored.state.value.saveError)

        restored.editConfirmed()
        restored.edit { it.copy(title = "Edited") }
        fixture.createError = null
        restored.save()
        advanceUntilIdle()
        assertEquals(listOf(null, 1L), fixture.writes.map { it.first })
        assertEquals("Edited", saved.draft?.title)
        assertEquals(2L, saved.draft?.revision)
    }

    @Test fun reconcileDistinguishesMissingFirstWriteExistingConflictAndTransientRead() = runTest(dispatcher) {
        saved.draft = fixture.draft().copy(uncertain = true)
        fixture.readError = DomainError.Timeout
        val first = model()
        advanceUntilIdle()
        first.save()
        advanceUntilIdle()
        assertEquals(DomainError.Timeout, first.state.value.saveError)
        assertTrue(requireNotNull(saved.draft).uncertain)
        assertTrue(fixture.writes.isEmpty())
        fixture.readError = null
        first.save()
        advanceUntilIdle()
        assertEquals(1, fixture.writes.size)
        assertTrue(requireNotNull(saved.draft).confirmed)

        saved.draft = fixture.draft().copy(uncertain = true, title = "Other content")
        fixture.stored = fixture.playlist(requireNotNull(saved.draft).id, fixture.draft().input(), 1L)
        val conflict = model("conflict")
        advanceUntilIdle()
        conflict.save()
        advanceUntilIdle()
        assertEquals(DomainError.VersionConflict, conflict.state.value.saveError)
        assertFalse(requireNotNull(saved.draft).uncertain)
        assertEquals(1, fixture.writes.size)

        saved.draft = fixture.draft().copy(uncertain = true, revision = 3L)
        fixture.stored = null
        val missing = model("missing")
        advanceUntilIdle()
        missing.save()
        advanceUntilIdle()
        assertEquals(DomainError.NotFound, missing.state.value.saveError)
        assertTrue(requireNotNull(saved.draft).uncertain)
        assertEquals(1, fixture.writes.size)
    }

    @Test fun validationAndOwnershipPreventWritesAndDefiniteRejectionUnlocksTheDraft() = runTest(dispatcher) {
        saved.draft = fixture.draft().copy(title = "   ")
        val model = model()
        advanceUntilIdle()
        model.save()
        assertTrue(fixture.writes.isEmpty())
        model.edit { it.copy(title = "Valid") }
        fixture.accountId.value = UUID.randomUUID()
        runCurrent()
        model.save()
        assertEquals(DomainError.Unauthenticated, model.state.value.saveError)
        assertTrue(fixture.writes.isEmpty())
        fixture.accountId.value = null
        runCurrent()
        fixture.accountId.value = fixture.owner
        runCurrent()
        assertNull(model.state.value.saveError)
        fixture.createError = DomainError.Forbidden
        model.save()
        advanceUntilIdle()
        assertEquals(DomainError.Forbidden, model.state.value.saveError)
        assertFalse(requireNotNull(saved.draft).uncertain)
        model.edit { it.copy(title = "Editable again") }
        assertEquals("Editable again", saved.draft?.title)
    }

    @Test fun lookupDebouncesPaginatesAndDoesNotReorderDuplicates() = runTest(dispatcher) {
        val model = model()
        val first = hit(SearchCategory.TRACK)
        val second = hit(SearchCategory.TRACK)
        fixture.pages[0] = Page(listOf(first), true)
        fixture.pages[20] = Page(listOf(first, second), false)
        val catalog = model.catalogActions
        catalog.lookup(SearchCategory.TRACK, "a")
        advanceTimeBy(200)
        catalog.lookup(SearchCategory.TRACK, " ambient ")
        catalog.lookup(SearchCategory.TRACK, "ambient", more = true)
        advanceUntilIdle()
        assertEquals(listOf("ambient" to 0), fixture.queries)
        assertTrue(model.state.value.lookup.hasMore)
        fixture.lookupError = DomainError.Timeout
        catalog.lookup(SearchCategory.TRACK, "ambient", more = true)
        advanceUntilIdle()
        assertEquals(listOf(first), model.state.value.lookup.items)
        assertEquals(DomainError.Timeout, model.state.value.lookup.error)
        fixture.lookupError = null
        catalog.lookup(SearchCategory.TRACK, "ambient", more = true)
        advanceUntilIdle()
        assertEquals(listOf(first, second), model.state.value.lookup.items)
        assertEquals(listOf(0, 20, 20), fixture.queries.map { it.second })
        assertFalse(model.state.value.lookup.hasMore)
        catalog.closeLookup()
        assertEquals(LookupState(), model.state.value.lookup)
    }

    @Test fun optionsRetryOnlyFailedFacetsAndCoalesceConcurrentRequests() = runTest(dispatcher) {
        val model = model()
        fixture.optionError = DomainError.Network
        val catalog = model.catalogActions
        repeat(2) { catalog.loadOptions(SearchFacet.Genre) }
        advanceUntilIdle()
        assertEquals(setOf(SearchFacet.Genre), model.state.value.optionErrors)
        assertEquals(listOf(SearchFacet.Genre), fixture.optionCalls)
        fixture.optionError = null
        catalog.loadOptions(SearchFacet.Genre)
        advanceUntilIdle()
        catalog.loadOptions(SearchFacet.Genre)
        advanceUntilIdle()
        assertEquals(2, fixture.optionCalls.size)
        assertEquals(listOf(SearchOption(3, "Ambient")), model.state.value.options[SearchFacet.Genre])
        assertTrue(model.state.value.optionErrors.isEmpty())
    }

    private fun model(key: String = "workshop") =
        PlaylistWorkshopViewModel(saved, fixture, fixture, fixture, fixture, fixture) { time }
            .also { store.put(key, it) }

    private fun hit(category: SearchCategory) =
        SearchHit(category, UUID.randomUUID(), "Selection", null, null, TrackAvailability.EXTERNAL_ONLY)

    private class MemoryWorkshopState(
        override var form: GenerationForm = GenerationForm(),
        override var draft: WorkshopDraft? = null,
    ) : WorkshopPersistence

    private class Fixture :
        ProfileSession, PlaylistGenerator, PlaylistRepository, GlobalSearchRepository, TrackMetadataReader {
        val owner = UUID.randomUUID()
        val track = Track(UUID.randomUUID(), "Ambient title", "Artist", 64.seconds, TrackAvailability.IN_APP)
        override val accountId = MutableStateFlow<UUID?>(owner)
        override val history = MutableStateFlow(emptyList<String>())
        val requests = mutableListOf<GenerationRequest>()
        val writes = mutableListOf<Pair<Long?, PlaylistInput>>()
        val reads = mutableListOf<UUID>()
        val queries = mutableListOf<Pair<String, Int>>()
        val optionCalls = mutableListOf<SearchFacet>()
        val pages = mutableMapOf<Int, Page<SearchHit>>()
        var stored: Playlist? = null
        var createError: DomainError? = null
        var readError: DomainError? = null
        var generateError: DomainError? = null
        var metadataError: DomainError? = null
        var lookupError: DomainError? = null
        var optionError: DomainError? = null
        var writeDespiteError = false
        var generationGate: CompletableDeferred<Unit>? = null

        fun draft() = WorkshopDraft(
            UUID.randomUUID(), owner, "Draft", "Description", listOf(EditableTrack(track.id, "reason")), emptyList(),
        )

        fun playlist(id: UUID, input: PlaylistInput, revision: Long) = Playlist(
            id, owner, input.title, input.visibility, input.trackIds.map { PlaylistItem(track.copy(id = it)) },
            revision, description = input.description,
        )

        override suspend fun restore() = Unit
        override suspend fun currentUser() = DomainResult.Success(accountId.value?.let { UserProfile(it, "Tester") })
        override suspend fun signIn(email: String, password: String) =
            DomainResult.Success(UserProfile(owner, "Tester"))
        override suspend fun signOut() { accountId.value = null }

        override suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> {
            requests += request
            generationGate?.await()
            return generateError?.let { DomainResult.Failure(it) } ?: DomainResult.Success(
                GeneratedPlaylistDraft(
                    "Preview", "Description", listOf(GeneratedItem(track.id, "reason", TrackAvailability.IN_APP)),
                ),
            )
        }

        override suspend fun create(id: UUID, input: PlaylistInput) = write(id, input, null)

        override suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput) =
            write(id, input, expectedRevision)

        private fun write(id: UUID, input: PlaylistInput, expected: Long?): DomainResult<Playlist> {
            writes += expected to input
            val playlist = playlist(id, input, (expected ?: 0L) + 1L)
            if (createError == null || writeDespiteError) stored = playlist
            return createError?.let { DomainResult.Failure(it) } ?: DomainResult.Success(playlist)
        }

        override suspend fun get(id: UUID): DomainResult<Playlist> {
            reads += id
            return readError?.let { DomainResult.Failure(it) } ?: stored?.let { DomainResult.Success(it) }
                ?: DomainResult.Failure(DomainError.NotFound)
        }

        override suspend fun read(ids: List<UUID>): DomainResult<List<TrackMetadata>> =
            metadataError?.let { DomainResult.Failure(it) } ?: DomainResult.Success(
                ids.map { TrackMetadata(it, track.title, track.artist, track.duration.inWholeMilliseconds) },
            )

        override suspend fun rememberQuery(query: String) = Unit
        override suspend fun clearHistory() = Unit

        override suspend fun options(facet: SearchFacet): DomainResult<List<SearchOption>> {
            optionCalls += facet
            return optionError?.let { DomainResult.Failure(it) }
                ?: DomainResult.Success(listOf(SearchOption(3, "Ambient")))
        }

        override suspend fun search(request: GlobalSearch, limit: Int, offset: Int): DomainResult<Page<SearchHit>> {
            queries += request.query to offset
            return lookupError?.let { DomainResult.Failure(it) } ?: DomainResult.Success(pages.getValue(offset))
        }
    }
}
