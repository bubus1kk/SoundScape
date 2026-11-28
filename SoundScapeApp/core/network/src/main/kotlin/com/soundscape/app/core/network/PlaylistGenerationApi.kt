package com.soundscape.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Tag

@Serializable
data class GenerationStopDto(val id: String, val kind: String, val position: Int)

@Serializable
data class GenerationDto(
    val mode: String,
    val count: Int,
    val onlyInApp: Boolean,
    val prompt: String? = null,
    val genre: String? = null,
    val mood: String? = null,
    val genreIds: List<Int> = emptyList(),
    val moodIds: List<Int> = emptyList(),
    val placeId: String? = null,
    val stops: List<GenerationStopDto> = emptyList(),
    val musicPinId: String? = null,
)

@Serializable
data class GeneratedTrackDto(
    val trackId: String,
    val position: Int,
    val reason: String? = null,
    val availability: String? = null,
)

@Serializable
data class GeneratedPlaylistDto(
    val title: String,
    val description: String,
    val items: List<GeneratedTrackDto>,
    val warnings: List<String>,
)

@Serializable
data class SavePlaylistDto(
    val playlistId: String,
    val title: String,
    val visibility: String,
    val trackIds: List<String>,
    val description: String? = null,
    val expectedRevision: Long? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
    val coverPath: String? = null,
)

@Serializable
data class TrackCreditDto(@SerialName("credit_order") val order: Int, val artists: TrackArtistDto?)

@Serializable
data class TrackArtistDto(val name: String)

@Serializable
data class CatalogMetadataDto(
    val id: String,
    val title: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("track_artists") val credits: List<TrackCreditDto> = emptyList(),
)

interface PlaylistGenerationApi {
    @Headers("X-Soundscape-Auth: Authenticated")
    @POST("functions/v1/v1-generate-playlist")
    suspend fun generate(
        @Body request: GenerationDto,
        @Tag credential: SessionCredential,
    ): Response<GeneratedPlaylistDto>

    @Headers("X-Soundscape-Auth: Authenticated")
    @POST("rest/v1/rpc/save_playlist")
    suspend fun save(
        @Body request: SavePlaylistDto,
        @Tag credential: SessionCredential,
    ): Response<PlaylistDetailDto>

    @Headers("X-Soundscape-Auth: Authenticated")
    @POST("rest/v1/rpc/get_playlist")
    suspend fun getOwned(
        @Body request: GetPlaylistRequestDto,
        @Tag credential: SessionCredential,
    ): Response<PlaylistDetailDto>

    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/tracks?select=id,title,duration_ms,track_artists(credit_order,artists(name))")
    suspend fun metadata(@Query("id") ids: String): Response<List<CatalogMetadataDto>>
}
