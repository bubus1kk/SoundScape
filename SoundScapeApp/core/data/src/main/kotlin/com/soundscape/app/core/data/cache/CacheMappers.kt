package com.soundscape.app.core.data.cache

import com.soundscape.app.core.database.CachedMapPlaceEntity
import com.soundscape.app.core.database.CachedTrackEntity
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.model.TrackAvailability
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

fun MapPlace.toEntity(scope: String): CachedMapPlaceEntity {
    require(title.isNotBlank())
    return CachedMapPlaceEntity(scope, kind.name, id.toString(), coordinates.latitude, coordinates.longitude,
        title, description, playlistId?.toString(), coverPath)
}
fun CachedMapPlaceEntity.toDomain(): MapPlace {
    val point = GeoPoint.create(latitude, longitude)
    require(point is DomainResult.Success && title.isNotBlank())
    return MapPlace(canonicalUuid(id), MapPlaceKind.valueOf(kind), title,
        (point as DomainResult.Success).value, description, playlistId?.let(::canonicalUuid), coverPath)
}
fun Track.toEntity(scope: String): CachedTrackEntity {
    require(validate() is DomainResult.Success)
    val millis = duration.inWholeMilliseconds
    require(millis > 0 && millis.milliseconds == duration)
    return CachedTrackEntity(scope, id.toString(), title, artist, millis, availability.name)
}
fun CachedTrackEntity.toDomain(): Track = Track(canonicalUuid(id), title, artist,
    durationMs.milliseconds, TrackAvailability.valueOf(availability)).also {
    require(it.validate() is DomainResult.Success)
}

private fun canonicalUuid(value: String): UUID = UUID.fromString(value).also {
    require(it.toString() == value)
}
