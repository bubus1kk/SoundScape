package com.soundscape.app.core.database

class CacheCapacityExceededException : IllegalStateException("Metadata cache capacity reached")

internal object CacheCapacity {
    const val PAGES = 200
    const val QUERIES = 1000
    const val METADATA_ROWS = 20000
}
