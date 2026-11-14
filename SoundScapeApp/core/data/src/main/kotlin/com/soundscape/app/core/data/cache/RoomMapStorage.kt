package com.soundscape.app.core.data.cache

import com.soundscape.app.core.database.CacheDao
import com.soundscape.app.core.database.CachedQueryPageEntity
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.MapPlace
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Atomic Room snapshots and observable invalidation; no service writes occur while observing. */
class RoomMapStorage @Inject constructor(private val dao: CacheDao) : PageStorage<MapPlace> {
    override suspend fun read(address: PageAddress): CachedPage<MapPlace>? =
        dao.mapSnapshot(address.scope, address.key, address.page.offset, address.page.limit)?.let {
            require(it.header.queryType == "map")
            CachedPage(Page(it.items.map { item -> item.toDomain() }, it.header.hasMore),
                Instant.ofEpochMilli(it.header.fetchedAt))
        }
    override fun observe(address: PageAddress) =
        dao.mapChanges(address.scope, address.key, address.page.offset, address.page.limit)
            .map { read(address) }.distinctUntilChanged()
    override suspend fun generation(address: PageAddress): Long =
        dao.refreshGeneration(address.scope, address.key, address.page.offset)
    override suspend fun save(address: PageAddress, generation: Long, page: Page<MapPlace>): Boolean {
        val items = page.items.map { it.toEntity(address.scope) }
        val header = CachedQueryPageEntity(address.scope, address.key, "map", address.page.offset,
            address.page.limit, generation, System.currentTimeMillis(), page.hasMore)
        return dao.savePlaces(header, items)
    }
}
