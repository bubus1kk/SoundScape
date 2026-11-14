package com.soundscape.app.core.data.cache

import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import kotlinx.coroutines.flow.Flow

data class PageAddress(val scope: String, val key: String, val page: PageRequest)

interface PageStorage<T> {
    suspend fun read(address: PageAddress): CachedPage<T>?
    fun observe(address: PageAddress): Flow<CachedPage<T>?>
    suspend fun generation(address: PageAddress): Long
    suspend fun save(address: PageAddress, generation: Long, page: Page<T>): Boolean
}
