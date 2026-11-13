package com.soundscape.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import java.util.UUID

/** Atomic page storage. All writers use these transactional entry points. */
// Room transaction entry points and their protected SQL primitives must share a DAO.
@Suppress("TooManyFunctions")
@Dao
abstract class CacheDao {
    /** Reads one page; null means absent, unlike an empty successful page. */
    @Query("SELECT * FROM cache_pages WHERE scope=:scope AND queryKey=:key AND `offset`=:offset AND `limit`=:limit")
    abstract suspend fun page(scope: String, key: String, offset: Int, limit: Int): CachedQueryPageEntity?

    /** Reads ordered map metadata for precisely one page. */
    @Query("""
        SELECT p.* FROM cached_places p JOIN map_page_items i ON p.scope=i.scope AND p.kind=i.kind AND
        p.id=i.id WHERE i.scope=:scope AND i.queryKey=:key AND i.`offset`=:offset AND i.`limit`=:limit
        ORDER BY i.position
    """)
    abstract suspend fun places(scope: String, key: String, offset: Int, limit: Int): List<CachedMapPlaceEntity>

    /** Reads ordered track metadata for precisely one page. */
    @Query("""
        SELECT t.* FROM cached_tracks t JOIN track_page_items i ON t.scope=i.scope AND t.id=i.id WHERE
        i.scope=:scope AND i.queryKey=:key AND i.`offset`=:offset AND i.`limit`=:limit ORDER BY
        i.position
    """)
    abstract suspend fun tracks(scope: String, key: String, offset: Int, limit: Int): List<CachedTrackEntity>

    /** Returns the current persisted refresh generation. */
    @Query("SELECT generation FROM cache_queries WHERE scope=:scope AND queryKey=:key")
    abstract suspend fun generation(scope: String, key: String): Long?

    /** Begins a new generation without deleting previously usable cached pages. */
    @Transaction
    open suspend fun beginRefresh(scope: String, key: String): Long {
        require(scope.isNotBlank() && key.isNotBlank())
        val next = Math.addExact(generation(scope, key) ?: 0, 1)
        putQuery(CacheQueryEntity(scope, key, next))
        return next
    }

    /** Rejects stale results before any mutation; replaces a complete map page atomically. */
    @Transaction
    open suspend fun savePlaces(page: CachedQueryPageEntity, items: List<CachedMapPlaceEntity>): Boolean {
        validate(page, items.size)
        require(page.queryType == "map" && page.queryKey.startsWith("v1:3:map"))
        if (generation(page.scope, page.queryKey) != page.generation) return false
        items.forEach {
            require(it.scope == page.scope && it.kind in setOf("CITY", "PIN"))
            uuid(it.id)
            it.playlistId?.let(::uuid)
            require(it.latitude in -CacheKeys.MAX_LATITUDE..CacheKeys.MAX_LATITUDE)
            require(it.longitude in -CacheKeys.MAX_LONGITUDE..CacheKeys.MAX_LONGITUDE)
        }
        prepare(page)
        putPlaces(items)
        putMapItems(items.mapIndexed { index, item ->
            MapPageItemEntity(page.scope, page.queryKey, page.offset, page.limit, index, item.kind, item.id)
        })
        return true
    }

    /** Rejects stale results before any mutation; replaces a complete track page atomically. */
    @Transaction
    open suspend fun saveTracks(page: CachedQueryPageEntity, items: List<CachedTrackEntity>): Boolean {
        validate(page, items.size)
        require(page.queryType == "search" && page.queryKey.startsWith("v1:6:search"))
        if (generation(page.scope, page.queryKey) != page.generation) return false
        items.forEach {
            require(it.scope == page.scope && it.durationMs > 0)
            require(it.availability in setOf("IN_APP", "EXTERNAL_ONLY"))
            uuid(it.id)
        }
        prepare(page)
        putTracks(items)
        putTrackItems(items.mapIndexed { index, item ->
            TrackPageItemEntity(page.scope, page.queryKey, page.offset, page.limit, index, item.id)
        })
        return true
    }

    /** Deletes ordinary cache pages older than a caller-selected cutoff, then orphan metadata. */
    @Transaction
    open suspend fun deleteBefore(cutoff: Long) {
        deleteOldPages(cutoff)
        deleteOrphanPlaces()
        deleteOrphanTracks()
    }

    private suspend fun prepare(page: CachedQueryPageEntity) {
        if (page.offset == 0) deleteOtherGenerations(page.scope, page.queryKey, page.generation)
        deletePage(page.scope, page.queryKey, page.offset, page.limit)
        insertPage(page)
    }

    private fun validate(page: CachedQueryPageEntity, count: Int) {
        require(page.scope.isNotBlank() && page.queryKey.startsWith("v1:"))
        require(page.offset >= 0 && page.limit > 0 && count <= page.limit)
        require(page.generation > 0 && page.fetchedAt >= 0)
    }

    private fun uuid(value: String) {
        require(UUID.fromString(value).toString() == value)
    }

    @Upsert protected abstract suspend fun putQuery(query: CacheQueryEntity)
    @Upsert protected abstract suspend fun putPlaces(items: List<CachedMapPlaceEntity>)
    @Upsert protected abstract suspend fun putTracks(items: List<CachedTrackEntity>)
    @Insert protected abstract suspend fun insertPage(page: CachedQueryPageEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun putMapItems(items: List<MapPageItemEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun putTrackItems(items: List<TrackPageItemEntity>)

    @Query("DELETE FROM cache_pages WHERE scope=:scope AND queryKey=:key AND `offset`=:offset AND `limit`=:limit")
    protected abstract suspend fun deletePage(scope: String, key: String, offset: Int, limit: Int)
    @Query("DELETE FROM cache_pages WHERE scope=:scope AND queryKey=:key AND generation<>:generation")
    protected abstract suspend fun deleteOtherGenerations(scope: String, key: String, generation: Long)
    @Query("DELETE FROM cache_pages WHERE fetchedAt < :cutoff")
    protected abstract suspend fun deleteOldPages(cutoff: Long)
    @Query("""
        DELETE FROM cached_places WHERE NOT EXISTS (SELECT 1 FROM map_page_items i WHERE
        i.scope=cached_places.scope AND i.kind=cached_places.kind AND i.id=cached_places.id)
    """)
    protected abstract suspend fun deleteOrphanPlaces()
    @Query("""
        DELETE FROM cached_tracks WHERE NOT EXISTS (SELECT 1 FROM track_page_items i WHERE
        i.scope=cached_tracks.scope AND i.id=cached_tracks.id)
    """)
    protected abstract suspend fun deleteOrphanTracks()
}
