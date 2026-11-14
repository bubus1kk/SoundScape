package com.soundscape.app.core.database

/** Explicit capacity failure: never evict active pages or metadata that future drafts may reference. */
class CacheCapacityExceededException : IllegalStateException("Metadata cache capacity reached")

/** Hard row budgets, not LRU or a byte-size guarantee. No automatic deletion is performed. */
internal object CacheCapacity {
    const val PAGES = 200
    const val QUERIES = 1000
    const val METADATA_ROWS = 20000
}
