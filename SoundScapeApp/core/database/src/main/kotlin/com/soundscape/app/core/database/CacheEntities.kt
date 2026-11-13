package com.soundscape.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Identity and monotonic refresh generation for one scoped query. */
@Entity(tableName = "cache_queries", primaryKeys = ["scope", "queryKey"])
data class CacheQueryEntity(val scope: String, val queryKey: String, val generation: Long)

/** Durable page header; a header without items is a successful empty result. */
@Entity(
    tableName = "cache_pages", primaryKeys = ["scope", "queryKey", "offset", "limit"],
    foreignKeys = [ForeignKey(entity = CacheQueryEntity::class,
        parentColumns = ["scope", "queryKey"], childColumns = ["scope", "queryKey"],
        onDelete = ForeignKey.CASCADE)],
)
data class CachedQueryPageEntity(
    val scope: String, val queryKey: String, val queryType: String,
    val offset: Int, val limit: Int, val generation: Long,
    val fetchedAt: Long, val hasMore: Boolean,
)

/** Scoped map summary. UUIDs are canonical strings; coordinates are degrees. */
@Entity(tableName = "cached_places", primaryKeys = ["scope", "kind", "id"])
data class CachedMapPlaceEntity(
    val scope: String, val kind: String, val id: String, val latitude: Double,
    val longitude: Double, val title: String, val description: String? = null,
    val playlistId: String? = null, val coverPath: String? = null,
)

/** Scoped track metadata; duration is stored in milliseconds, never audio bytes. */
@Entity(tableName = "cached_tracks", primaryKeys = ["scope", "id"])
data class CachedTrackEntity(
    val scope: String, val id: String, val title: String, val artist: String,
    val durationMs: Long, val availability: String,
)

/** Ordered membership with same-scope foreign keys to the page and metadata. */
@Entity(
    tableName = "map_page_items",
    primaryKeys = ["scope", "queryKey", "offset", "limit", "position"],
    foreignKeys = [
        ForeignKey(entity = CachedQueryPageEntity::class,
            parentColumns = ["scope", "queryKey", "offset", "limit"],
            childColumns = ["scope", "queryKey", "offset", "limit"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CachedMapPlaceEntity::class,
            parentColumns = ["scope", "kind", "id"], childColumns = ["scope", "kind", "id"]),
    ],
    indices = [Index(value = ["scope", "kind", "id"]),
        Index(value = ["scope", "queryKey", "offset", "limit", "kind", "id"], unique = true)],
)
data class MapPageItemEntity(val scope: String, val queryKey: String, val offset: Int, val limit: Int,
    val position: Int, val kind: String, val id: String)

/** Ordered membership with same-scope foreign keys to the page and metadata. */
@Entity(
    tableName = "track_page_items",
    primaryKeys = ["scope", "queryKey", "offset", "limit", "position"],
    foreignKeys = [
        ForeignKey(entity = CachedQueryPageEntity::class,
            parentColumns = ["scope", "queryKey", "offset", "limit"],
            childColumns = ["scope", "queryKey", "offset", "limit"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CachedTrackEntity::class,
            parentColumns = ["scope", "id"], childColumns = ["scope", "id"]),
    ],
    indices = [Index(value = ["scope", "id"]),
        Index(value = ["scope", "queryKey", "offset", "limit", "id"], unique = true)],
)
data class TrackPageItemEntity(val scope: String, val queryKey: String, val offset: Int, val limit: Int,
    val position: Int, val id: String)
