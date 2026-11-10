package com.soundscape.app.core.domain.model

data class PageRequest(val limit: Int = 20, val offset: Int = 0)

data class Page<T>(val items: List<T>, val hasMore: Boolean)

data class MusicSearch(
    val query: String,
    val onlyInApp: Boolean = false,
    val genreIds: List<Int> = emptyList(),
    val moodIds: List<Int> = emptyList(),
    val page: PageRequest = PageRequest(),
)
