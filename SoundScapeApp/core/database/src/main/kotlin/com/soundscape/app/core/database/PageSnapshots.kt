package com.soundscape.app.core.database

/** Header and ordered metadata read within a single SQLite transaction. */
data class MapPageSnapshot(val header: CachedQueryPageEntity, val items: List<CachedMapPlaceEntity>)
/** Header and ordered metadata read within a single SQLite transaction. */
data class TrackPageSnapshot(val header: CachedQueryPageEntity, val items: List<CachedTrackEntity>)
