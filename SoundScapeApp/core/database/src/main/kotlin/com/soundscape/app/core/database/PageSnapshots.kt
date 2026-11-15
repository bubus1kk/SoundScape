package com.soundscape.app.core.database

data class MapPageSnapshot(val header: CachedQueryPageEntity, val items: List<CachedMapPlaceEntity>)
data class TrackPageSnapshot(val header: CachedQueryPageEntity, val items: List<CachedTrackEntity>)
