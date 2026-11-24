package com.soundscape.app.presentation.map

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class MapInteractionTest {
    @Test fun releaseBalancesAnActiveMapAndPreventsDuplicateTeardown() {
        val lifecycle = MapLifecycleState()
        val events = mutableListOf<String>()
        lifecycle.start { events += "start" }
        lifecycle.resume { events += "resume" }
        val pause = { lifecycle.pause({ events += "save" }, { events += "pause" }) }
        val stop = { lifecycle.stop { events += "stop" } }

        assertTrue(lifecycle.release(pause, stop))
        assertTrue(lifecycle.released)
        assertFalse(lifecycle.release(pause, stop))
        assertEquals(listOf("start", "resume", "save", "pause", "stop"), events)
    }

    @Test fun alreadyStoppedOrNeverAttachedMapDoesNotSendExtraLifecycleEvents() {
        val lifecycle = MapLifecycleState()
        lifecycle.start {}
        lifecycle.resume {}
        lifecycle.pause({}, {})
        lifecycle.stop {}
        assertTrue(lifecycle.release({ error("Already paused") }, { error("Already stopped") }))
        assertTrue(MapLifecycleState().release({ error("Never resumed") }, { error("Never started") }))
    }

    @Test fun zoomControlsAndFlyToRespectTheNativeCameraLimits() {
        val range = MapZoomRange(2.0, 15.0)
        assertEquals(2.0, range.step(2.5, -1.0), 0.0)
        assertEquals(15.0, range.step(14.5, 1.0), 0.0)
        assertFalse(range.canZoomOut(2.00001))
        assertFalse(range.canZoomIn(14.99999))
        assertTrue(range.canZoomOut(2.1))
        assertTrue(range.canZoomIn(14.9))
        assertEquals(12.0, range.destination(3.0), 0.0)
        assertEquals(14.0, range.destination(14.0), 0.0)
        assertEquals(10.0, MapZoomRange(2.0, 10.0).destination(3.0), 0.0)
        assertEquals(7.0, range.overview(9.0, null), 0.0)
        assertEquals(4.0, range.overview(9.0, 4.0), 0.0)
        assertEquals(2.0, range.overview(3.0, 1.0), 0.0)
    }

    @Test fun pinSelectionAndEmptyMapClicksAreDistinctFromClusterExpansion() {
        val id = UUID.randomUUID()
        val point = Point.fromLngLat(12.0, 45.0)
        val selected = mutableListOf<UUID?>()
        val expanded = mutableListOf<Pair<Point, Double>>()
        val pin = Feature.fromGeometry(point, null, id.toString())
        val cluster = Feature.fromGeometry(point).apply { addNumberProperty("cluster_id", 42) }

        assertTrue(dispatchMarkerClick(pin, { error("Pin is not a cluster") }, { selected += it }) { _, _ ->
            error("A pin is selected, never expanded")
        })
        assertFalse(dispatchMarkerClick(null, { error("No feature") }, { selected += it }) { _, _ ->
            error("Empty map must not animate")
        })
        assertTrue(dispatchMarkerClick(cluster, { 8 }, { error("Cluster must not select a pin") }) { at, zoom ->
            expanded += at to zoom
        })

        assertEquals(listOf(id, null), selected)
        assertEquals(listOf(point to 8.0), expanded)
    }

    @Test fun invalidClusterGeometryAndUnavailableExpansionDoNotAnimate() {
        val first = Point.fromLngLat(10.0, 45.0)
        val second = Point.fromLngLat(11.0, 46.0)
        val malformed = Feature.fromGeometry(LineString.fromLngLats(listOf(first, second))).apply {
            addNumberProperty("cluster_id", 42)
        }
        val cluster = Feature.fromGeometry(first).apply { addNumberProperty("cluster_id", 42) }

        assertFalse(dispatchMarkerClick(malformed, { error("Invalid geometry") }, { error("Cluster") }) { _, _ ->
            error("Invalid geometry cannot be expanded")
        })
        assertTrue(dispatchMarkerClick(cluster, { -1 }, { error("Cluster") }) { _, _ ->
            error("Unavailable expansion cannot be animated")
        })
    }
}
