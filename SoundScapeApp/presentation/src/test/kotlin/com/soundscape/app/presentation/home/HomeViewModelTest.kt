package com.soundscape.app.presentation.home

import androidx.lifecycle.ViewModelStore
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.CachedPageState
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.repository.VisiblePlacesCache
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    private class Fake : VisiblePlacesCache {
        val snapshot = MutableStateFlow(CachedPageState<MapPlace>())
        var remote = Page<MapPlace>(emptyList(), false)
        val calls = mutableListOf<PageRequest>()
        var failure: DomainError? = null
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = false
        override fun observe(bounds: GeoBounds, page: PageRequest) = snapshot
        override suspend fun refresh(bounds: GeoBounds, page: PageRequest): DomainResult<Unit> {
            assertEquals(-90.0, bounds.southWest.latitude, 0.0)
            assertEquals(-180.0, bounds.southWest.longitude, 0.0)
            assertEquals(90.0, bounds.northEast.latitude, 0.0)
            assertEquals(180.0, bounds.northEast.longitude, 0.0)
            calls += page
            try { gate?.await() } catch (error: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw error
            }
            failure?.let { return DomainResult.Failure(it) }
            snapshot.value = CachedPageState(CachedPage(remote, Instant.EPOCH))
            return DomainResult.Success(Unit)
        }
    }
    private fun model(fake: Fake) = HomeViewModel(ObserveVisiblePlacesUseCase(fake), RefreshVisiblePlacesUseCase(fake))
    private fun place(kind: MapPlaceKind = MapPlaceKind.CITY) = MapPlace(UUID.randomUUID(), kind, "Place",
        (GeoPoint.create(0.0, 0.0) as DomainResult.Success).value)

    @Test fun loadingToContentFiltersPinsAndLimitsUniqueCities() = runTest(dispatcher) {
        val city = place()
        val fake = Fake().apply {
            remote = Page(listOf(city, city, place(MapPlaceKind.PIN)) + List(22) { place() }, true)
        }
        val vm = model(fake)
        assertEquals(HomeUiState.Loading, vm.state.value)
        runCurrent()
        val state = vm.state.value as HomeUiState.Content
        assertEquals(20, state.cities.size)
        assertEquals(20, state.cities.map { it.id }.distinct().size)
        assertTrue(state.cities.all { it.kind == MapPlaceKind.CITY })
        assertFalse(state.fromCache)
        assertFalse(state.refreshing)
        assertEquals(listOf(PageRequest(100, 0)), fake.calls)
    }
    @Test fun successfulPinOrEmptyPageIsEmptyRegardlessOfHasMore() = runTest(dispatcher) {
        for (items in listOf(emptyList(), listOf(place(MapPlaceKind.PIN)))) {
            for (more in listOf(false, true)) {
                val fake = Fake().apply { remote = Page(items, more) }
                val vm = model(fake); runCurrent()
                assertEquals(HomeUiState.Empty(), vm.state.value)
                assertEquals(listOf(PageRequest(100, 0)), fake.calls)
            }
        }
    }
    @Test fun loadingToErrorAndRetryRecoversWithoutDuplicateRequests() = runTest(dispatcher) {
        val fake = Fake().apply { failure = DomainError.Network }
        val vm = model(fake); runCurrent()
        assertEquals(HomeUiState.Error(DomainError.Network), vm.state.value)
        fake.failure = null
        fake.remote = Page(listOf(place()), false)
        fake.gate = CompletableDeferred()
        repeat(5) { vm.retry() }; runCurrent()
        assertEquals(2, fake.calls.size)
        fake.gate!!.complete(Unit); runCurrent()
        assertTrue(vm.state.value is HomeUiState.Content)
    }
    @Test fun cachedCitiesSurviveOfflineAndRecreatedViewModel() = runTest(dispatcher) {
        val city = place().copy(coverPath = "places/city.webp")
        val fake = Fake().apply { remote = Page(listOf(city), true) }
        val first = model(fake); runCurrent()
        assertEquals(listOf(city), (first.state.value as HomeUiState.Content).cities)
        fake.failure = DomainError.Network
        first.retry(); runCurrent()
        assertEquals(DomainError.Network, (first.state.value as HomeUiState.Content).refreshError)
        val reopened = model(fake); runCurrent()
        val state = reopened.state.value as HomeUiState.Content
        assertEquals(listOf(city), state.cities)
        assertTrue(state.fromCache)
        assertEquals(DomainError.Network, state.refreshError)
        assertFalse(state.refreshing)
    }
    @Test fun cachedContentRemainsVisibleDuringRefreshAndCancellation() = runTest(dispatcher) {
        val fake = Fake().apply {
            snapshot.value = CachedPageState(CachedPage(Page(listOf(place()), false), Instant.EPOCH))
            gate = CompletableDeferred()
        }
        val vm = model(fake)
        val store = ViewModelStore().apply { put("home", vm) }
        runCurrent()
        assertTrue((vm.state.value as HomeUiState.Content).refreshing)
        store.clear(); runCurrent()
        assertTrue(fake.cancelled)
        val state = vm.state.value as HomeUiState.Content
        assertFalse(state.refreshing)
        assertNull(state.refreshError)
    }
    @Test fun retryReplacesSnapshotRatherThanAppendingOldCities() = runTest(dispatcher) {
        val fresh = place()
        val fake = Fake().apply { remote = Page(listOf(place()), false) }
        val vm = model(fake); runCurrent()
        fake.remote = Page(listOf(fresh), true)
        vm.retry(); runCurrent()
        assertEquals(listOf(fresh), (vm.state.value as HomeUiState.Content).cities)
        fake.remote = Page(emptyList(), true)
        vm.retry(); runCurrent()
        assertEquals(HomeUiState.Empty(), vm.state.value)
    }
    @Test fun cancellationWithoutCachedContentDoesNotBecomeError() = runTest(dispatcher) {
        val fake = Fake().apply { gate = CompletableDeferred() }
        val vm = model(fake); runCurrent()
        assertEquals(HomeUiState.Loading, vm.state.value)
        val store = ViewModelStore().apply { put("home", vm) }
        store.clear(); runCurrent()
        assertTrue(fake.cancelled)
        assertEquals(HomeUiState.Loading, vm.state.value)
    }
    @Test fun cachedEmptySurvivesOfflineAndRetry() = runTest(dispatcher) {
        val fake = Fake().apply {
            snapshot.value = CachedPageState(CachedPage(Page(emptyList(), false), Instant.EPOCH))
            failure = DomainError.Network
        }
        val vm = model(fake); runCurrent()
        assertEquals(HomeUiState.Empty(fromCache = true, refreshError = DomainError.Network), vm.state.value)
        fake.failure = null
        vm.retry(); runCurrent()
        assertEquals(HomeUiState.Empty(), vm.state.value)
    }
}
