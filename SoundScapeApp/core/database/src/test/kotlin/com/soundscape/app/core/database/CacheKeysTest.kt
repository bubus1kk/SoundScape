package com.soundscape.app.core.database

import org.junit.Assert.assertNotEquals
import org.junit.Test

class CacheKeysTest {
    @Test fun encodingPreservesBoundariesAndFilters() {
        assertNotEquals(CacheKeys.search("a:1", false, listOf(1, 2), emptyList()),
            CacheKeys.search("a", false, listOf(12), emptyList()))
        assertNotEquals(CacheKeys.search("a", false, listOf(1), listOf(2)),
            CacheKeys.search("a", false, listOf(2), listOf(1)))
        assertNotEquals(CacheKeys.map(0.0, 170.0, 10.0, -170.0),
            CacheKeys.map(0.0, -170.0, 10.0, 170.0))
    }
}
