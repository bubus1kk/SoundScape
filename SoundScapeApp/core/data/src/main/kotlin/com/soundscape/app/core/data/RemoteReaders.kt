@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.Track
import com.soundscape.app.core.domain.repository.TrackSearcher
import com.soundscape.app.core.domain.repository.VisiblePlacesReader
import com.soundscape.app.core.network.VisiblePlacesRequestDto
import com.soundscape.app.core.network.PlaceSummaryDto
import com.soundscape.app.core.network.SearchTracksRequestDto
import com.soundscape.app.core.network.TrackViewDto
import com.soundscape.app.core.network.PlacesApi
import com.soundscape.app.core.network.TracksApi
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.ConnectivityChecker
import dagger.Lazy
import java.io.EOFException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.io.InterruptedIOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.Response
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun parseUuid(value: String): UUID = UUID.fromString(value).also {
    require(it.toString().equals(value, ignoreCase = true))
}

fun PlaceSummaryDto.toDomain(): MapPlace {
    val point = GeoPoint.create(latitude, longitude)
    require(point is DomainResult.Success)
    require(title.isNotBlank())
    return MapPlace(parseUuid(id), MapPlaceKind.valueOf(kind), title, point.value,
        description, playlistId?.let(::parseUuid), coverPath)
}
fun TrackViewDto.toDomain(): Track {
    val track = Track(parseUuid(id), title, artist, durationMs.milliseconds,
        TrackAvailability.valueOf(availability))
    require(track.validate() is DomainResult.Success)
    return track
}

class NetworkErrorMapper @Inject constructor(private val json: Json) {
    @Suppress("ReturnCount")
    private fun retryAfter(value: String?): kotlin.time.Duration? {
        if (value == null) return null
        value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds in 0..86400 }?.seconds }
        return runCatching {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            ((date.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
                .takeIf { it <= 86400 }?.seconds
        }.getOrNull()
    }
    fun http(status: Int, body: String?, retry: String?): DomainError {
        val root = runCatching { json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject ?: root
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
        return when {
            status >= 500 -> DomainError.Server
            status == 429 -> DomainError.RateLimited(retryAfter(retry))
            else -> clientError(status, code, message)
        }
    }
    private fun clientError(status: Int, code: String?, message: String?): DomainError = when (status) {
        401 -> sessionError(code, message)
        403 -> DomainError.Forbidden
        404 -> DomainError.NotFound
        409 -> when (message) {
            "OBJECT_ALREADY_EXISTS" -> DomainError.AlreadyExists
            "VERSION_CONFLICT" -> DomainError.VersionConflict
            else -> DomainError.Unexpected
        }
        400, 422 -> validationError(code, message)
        else -> DomainError.Unexpected
    }
    private fun sessionError(code: String?, message: String?): DomainError =
        if (code in setOf("AUTH_REQUIRED", "INVALID_JWT", "PGRST301", "PGRST303") ||
            code == "PT401" && message == "AUTH_REQUIRED") DomainError.Unauthenticated
        else DomainError.Unexpected
    private fun validationError(code: String?, message: String?): DomainError =
        if (code == "VALIDATION_ERROR" ||
            code == "PT422" && message in setOf("INVALID_BOUNDS", "INVALID_PAGINATION"))
            DomainError.Validation("request", "invalid_request")
        else DomainError.Unexpected

}
class RemoteCall @Inject constructor(
    private val config: NetworkConfig, private val connectivity: ConnectivityChecker,
    private val errors: NetworkErrorMapper,
) {
    @Suppress("ReturnCount")
    suspend fun <T : Any, R> execute(request: suspend () -> Response<T>, map: (T) -> R): DomainResult<R> {
        if (!config.ready) return DomainResult.Failure(DomainError.Unexpected)
        if (connectivity.isOffline()) return DomainResult.Failure(DomainError.Network)
        return try {
            val response = request()
            if (response.isSuccessful) {
                val body = response.body() ?: return DomainResult.Failure(DomainError.Unexpected)
                DomainResult.Success(map(body))
            } else {
                val body = response.errorBody()?.use {
                    val size = minOf(it.contentLength().takeIf { n -> n >= 0 } ?: 16384L, 16384L)
                    it.source().request(size)
                    it.source().buffer.clone().readUtf8(minOf(size, it.source().buffer.size))
                }
                DomainResult.Failure(errors.http(response.code(), body, response.headers()["Retry-After"]))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SerializationException) {
            DomainResult.Failure(DomainError.Unexpected)
        } catch (_: EOFException) {
            DomainResult.Failure(DomainError.Unexpected)
        } catch (_: InterruptedIOException) {
            DomainResult.Failure(DomainError.Timeout)
        } catch (_: IOException) {
            DomainResult.Failure(DomainError.Network)
        } catch (_: IllegalArgumentException) {
            DomainResult.Failure(DomainError.Unexpected)
        }
    }
}
class RemoteVisiblePlacesReader @Inject constructor(
    private val api: Lazy<PlacesApi>, private val call: RemoteCall,
) : VisiblePlacesReader {
    override suspend fun getVisible(bounds: GeoBounds, page: PageRequest): DomainResult<Page<MapPlace>> {
        if (page.limit !in 1..100 || page.offset < 0)
            return DomainResult.Failure(DomainError.Validation("page", "outside_range"))
        return call.execute({ api.get().visible(VisiblePlacesRequestDto(
            bounds.southWest.latitude, bounds.southWest.longitude,
            bounds.northEast.latitude, bounds.northEast.longitude, page.limit, page.offset)) }) {
            require(it.limit == page.limit && it.offset == page.offset)
            Page(it.items.map { item -> item.toDomain() }, it.hasMore)
        }
    }
}
class RemoteTrackSearcher @Inject constructor(
    private val api: Lazy<TracksApi>, private val call: RemoteCall,
) : TrackSearcher {
    @Suppress("ReturnCount") // Reject invalid inputs before sending any HTTP request.
    override suspend fun search(parameters: MusicSearch): DomainResult<Page<Track>> {
        if (parameters.page.offset !in 0..1000 || parameters.page.limit !in 1..50)
            return DomainResult.Failure(DomainError.Validation("page", "outside_range"))
        if (parameters.query.isBlank() || parameters.query.length > 200)
            return DomainResult.Failure(DomainError.Validation("query", "length_1_to_200"))
        for ((field, ids) in listOf("genreIds" to parameters.genreIds, "moodIds" to parameters.moodIds)) {
            if (ids.size > 20 || ids.any { it !in 1..32767 } || ids.distinct().size != ids.size)
                return DomainResult.Failure(DomainError.Validation(field, "invalid_ids"))
        }
        return call.execute({ api.get().search(SearchTracksRequestDto(parameters.query,
            parameters.onlyInApp, parameters.page.limit, parameters.page.offset,
            parameters.genreIds, parameters.moodIds)) }) {
            require(it.limit == parameters.page.limit && it.offset == parameters.page.offset)
            Page(it.items.map { item -> item.toDomain() }, it.hasMore)
        }
    }
}
