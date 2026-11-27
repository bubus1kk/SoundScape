package com.soundscape.app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@Serializable
data class GetPlaylistRequestDto(
    val playlistId: String,
)

@Serializable
data class PlaylistItemDto(
    val position: Int,
    val track: TrackViewDto,
)

@Serializable
@Suppress("LongParameterList")
data class PlaylistDetailDto(
    val id: String,
    val ownerId: String,
    val title: String,
    val visibility: String,
    val revision: Long,
    val items: List<PlaylistItemDto>,
    val description: String? = null,
    val placeId: String? = null,
    val coverPath: String? = null,
    val genreIds: List<Int> = emptyList(),
    val tagIds: List<Int> = emptyList(),
    val isEditorial: Boolean = false,
)

interface PlaylistsApi {
    @Headers("X-Soundscape-Auth: OptionalUser")
    @POST("rest/v1/rpc/get_playlist")
    suspend fun get(
        @Body request: GetPlaylistRequestDto,
    ): Response<PlaylistDetailDto>
}
