package com.soundscape.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CacheQueryEntity::class, CachedQueryPageEntity::class,
    CachedMapPlaceEntity::class, CachedTrackEntity::class,
    MapPageItemEntity::class, TrackPageItemEntity::class], version = 1, exportSchema = true)
abstract class SoundScapeDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
