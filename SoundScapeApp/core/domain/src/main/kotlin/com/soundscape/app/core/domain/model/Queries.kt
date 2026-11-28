package com.soundscape.app.core.domain.model

/** Requested page size and starting offset. */
data class PageRequest(val limit: Int = 20, val offset: Int = 0)

/** A page of items with an indication that more may be available. */
data class Page<T>(val items: List<T>, val hasMore: Boolean)

/** Search text and catalog filters for a page of tracks. */
data class MusicSearch(
    val query: String,
    val onlyInApp: Boolean = false,
    val genreIds: List<Int> = emptyList(),
    val moodIds: List<Int> = emptyList(),
    val page: PageRequest = PageRequest(),
)
