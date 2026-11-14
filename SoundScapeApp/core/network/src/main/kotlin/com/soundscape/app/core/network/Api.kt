package com.soundscape.app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Headers

@Serializable
data class VisiblePlacesRequestDto(
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
    val limit: Int, val offset: Int,
)
@Serializable
data class PlaceSummaryDto(
    val id: String, val kind: String, val title: String, val latitude: Double, val longitude: Double,
    val description: String? = null, val playlistId: String? = null, val coverPath: String? = null,
)
@Serializable
data class PlacePageDto(val items: List<PlaceSummaryDto>, val limit: Int, val offset: Int, val hasMore: Boolean)
@Serializable
data class SearchTracksRequestDto(
    val query: String, val onlyInApp: Boolean, val limit: Int, val offset: Int,
    val genreIds: List<Int>, val moodIds: List<Int>,
)
@Serializable
data class TrackViewDto(
    val id: String, val title: String, val artist: String, val durationMs: Long,
    val availability: String, val provider: String? = null,
)
@Serializable
data class CatalogPageDto(val items: List<TrackViewDto>, val limit: Int, val offset: Int, val hasMore: Boolean)
interface PlacesApi {
    @Headers("X-Soundscape-Guest: true")
    @POST("rest/v1/rpc/get_visible_places")
    suspend fun visible(@Body request: VisiblePlacesRequestDto): Response<PlacePageDto>
}
interface TracksApi {
    @Headers("X-Soundscape-Guest: true")
    @POST("functions/v1/v1-search-tracks")
    suspend fun search(@Body request: SearchTracksRequestDto): Response<CatalogPageDto>
}
