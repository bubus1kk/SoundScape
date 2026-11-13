package com.soundscape.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/** Version-one metadata cache. Future schema changes require explicit migrations. */
@Database(entities = [CacheQueryEntity::class, CachedQueryPageEntity::class,
    CachedMapPlaceEntity::class, CachedTrackEntity::class,
    MapPageItemEntity::class, TrackPageItemEntity::class], version = 1, exportSchema = true)
abstract class SoundScapeDatabase : RoomDatabase() {
    /** Scoped cache operations; no network or domain dependencies. */
    abstract fun cacheDao(): CacheDao
}
