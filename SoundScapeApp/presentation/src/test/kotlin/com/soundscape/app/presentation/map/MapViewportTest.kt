package com.soundscape.app.presentation.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {
    @Test fun roundsOutAndKeepsSmallCameraChangesInTheSameArea() {
        val first = requireNotNull(MapViewport(40.11, 2.27, 45.04, 14.93).queryBounds())
        val second = MapViewport(40.12, 2.30, 45.05, 14.94).queryBounds()
        assertEquals(first, second)
        assertEquals(40.0, first.southWest.latitude, 0.0)
        assertEquals(2.25, first.southWest.longitude, 0.0)
        assertEquals(45.25, first.northEast.latitude, 0.0)
        assertEquals(15.0, first.northEast.longitude, 0.0)
    }

    @Test fun keepsBothRepresentationsOfTheAntimeridianAsOneQuery() {
        val raw = requireNotNull(MapViewport(-20.0, 170.0, 20.0, 190.0).queryBounds())
        assertEquals(raw, MapViewport(-20.0, 170.0, 20.0, -170.0).queryBounds())
        assertEquals(raw, MapViewport(-20.0, -190.0, 20.0, -170.0).queryBounds())
        assertTrue(raw.crossesAntimeridian)
        assertEquals(-170.0, raw.northEast.longitude, 0.0)
    }

    @Test fun doesNotQueryWorldOrInvalidViewports() {
        assertNull(MapViewport(-80.0, -180.0, 80.0, 180.0).queryBounds())
        assertNull(MapViewport(-20.0, -360.0, 20.0, 360.0).queryBounds())
        assertNull(MapViewport(-20.0, -80.0, 20.0, 80.0).queryBounds())
        assertNull(MapViewport(30.0, 15.0, -20.0, 45.0).queryBounds())
        assertNull(MapViewport(Double.NaN, 15.0, 20.0, 45.0).queryBounds())
    }
}
