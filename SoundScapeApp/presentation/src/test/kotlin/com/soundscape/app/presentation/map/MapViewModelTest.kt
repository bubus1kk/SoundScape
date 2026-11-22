package com.soundscape.app.presentation.map

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
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.domain.usecase.GetVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class MapViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val first = requireNotNull(MapViewport(10.0, 10.0, 15.0, 15.0).queryBounds())
    private val second = requireNotNull(MapViewport(30.0, 30.0, 35.0, 35.0).queryBounds())

    @Before fun setup() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun debouncesAndDoesNotLoadUnchangedBounds() = runTest(dispatcher) {
        val source = Source()
        val model = model(source)
        model.onCameraIdle(first)
        advanceTimeBy(120)
        model.onCameraIdle(second)
        advanceTimeBy(299)
        runCurrent()
        assertTrue(source.reads.isEmpty())
        model.onCameraIdle(second)
        advanceTimeBy(1)
        runCurrent()
        model.onCameraMove()
        model.onCameraIdle(second)
        advanceTimeBy(310)
        runCurrent()
        assertEquals(listOf(second to PageRequest(100, 0)), source.reads)
    }

    @Test fun movingCancelsAnInFlightAreaAndDoesNotPublishItsResult() = runTest(dispatcher) {
        val oldPlace = place(MapPlaceKind.CITY)
        val currentPlace = place(MapPlaceKind.PIN)
        val gate = CompletableDeferred<DomainResult<Page<MapPlace>>>()
        val source = Source().apply {
            read = { bounds, _ ->
                if (bounds == first) gate.await() else DomainResult.Success(Page(listOf(currentPlace), false))
            }
        }
        val model = model(source)
        model.onCameraIdle(first)
        advanceTimeBy(300)
        runCurrent()
        model.onCameraMove()
        gate.complete(DomainResult.Success(Page(listOf(oldPlace), false)))
        model.onCameraIdle(second)
        runCurrent()
        assertTrue(model.state.value.places.isEmpty())
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(currentPlace), model.state.value.places)
    }

    @Test fun limitsPagesDeduplicatesUuidAndSelectsAnObjectWithoutPlaylist() = runTest(dispatcher) {
        val city = place(MapPlaceKind.CITY)
        val pin = place(MapPlaceKind.PIN)
        val source = Source().apply {
            read = { _, page -> DomainResult.Success(Page(
                if (page.offset == 0) listOf(city) else listOf(city, pin), true,
            )) }
        }
        val model = model(source)
        model.onCameraIdle(first)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(0, 100, 200), source.reads.map { it.second.offset })
        assertEquals(listOf(city, pin), model.state.value.places)
        assertTrue(model.state.value.partial)
        model.select(pin.id)
        assertEquals(pin, model.state.value.selected)
        assertNull(model.state.value.selected?.playlistId)
        model.select(null)
        assertNull(model.state.value.selected)
    }

    @Test fun distinguishesInitialBackgroundAndRetryAndKeepsMarkersUntilReplacement() = runTest(dispatcher) {
        val oldCity = place(MapPlaceKind.CITY)
        val newCity = place(MapPlaceKind.CITY)
        val source = Source()
        val vm = model(source)
        source.read = { _, _ -> DomainResult.Success(Page(listOf(oldCity), false)) }
        vm.onCameraIdle(first)
        assertEquals(MapPlacesLoad.Initial, vm.state.value.load)
        advanceTimeBy(300)
        runCurrent()

        source.cache = CachedPage(Page(listOf(newCity), false), Instant.EPOCH)
        val gate = CompletableDeferred<Unit>()
        source.read = { _, _ ->
            gate.await()
            DomainResult.Failure(DomainError.Network)
        }
        vm.onCameraMove()
        vm.onCameraIdle(second)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(MapPlacesLoad.Background, vm.state.value.load)
        assertEquals(listOf(oldCity), vm.state.value.places)
        gate.complete(Unit)
        runCurrent()
        assertEquals(DomainError.Network, vm.state.value.error)
        assertEquals(listOf(newCity), vm.state.value.places)

        vm.retry()
        assertEquals(MapPlacesLoad.Retry, vm.state.value.load)
        runCurrent()
        assertEquals(3, source.reads.size)
    }

    @Test fun keepsCachedPlacesWhenRefreshFailsAndSkipsWorldBounds() = runTest(dispatcher) {
        val city = place(MapPlaceKind.CITY)
        val page = Page(listOf(city), false)
        val source = Source().apply {
            cache = CachedPage(page, Instant.EPOCH)
            read = { _, _ -> DomainResult.Success(page) }
        }
        val model = model(source)
        model.onCameraIdle(first)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(city), model.state.value.places)
        assertEquals(DomainError.Network, model.state.value.error)
        assertFalse(model.state.value.loading)
        model.onCameraIdle(null)
        assertTrue(model.state.value.needsZoom)
        assertTrue(model.state.value.places.isEmpty())
        assertEquals(1, source.reads.size)
    }

    private fun model(source: Source) = MapViewModel(GetVisiblePlacesUseCase(source),
        ObserveVisiblePlacesUseCase(source), RefreshVisiblePlacesUseCase(source)).also { store.put("map", it) }

    private fun place(kind: MapPlaceKind) = MapPlace(UUID.randomUUID(), kind, "Place",
        (GeoPoint.create(10.0, 10.0) as DomainResult.Success).value)

    private class Source : VisiblePlacesReader, VisiblePlacesCache {
        val reads = mutableListOf<Pair<GeoBounds, PageRequest>>()
        var cache: CachedPage<MapPlace>? = null
        var read: suspend (GeoBounds, PageRequest) -> DomainResult<Page<MapPlace>> =
            { _, _ -> DomainResult.Success(Page(emptyList(), false)) }

        override suspend fun getVisible(bounds: GeoBounds, page: PageRequest): DomainResult<Page<MapPlace>> {
            reads += bounds to page
            return read(bounds, page)
        }

        override fun observe(bounds: GeoBounds, page: PageRequest) = flowOf(CachedPageState(cache))

        override suspend fun refresh(bounds: GeoBounds, page: PageRequest) = DomainResult.Failure(DomainError.Network)
    }
}
