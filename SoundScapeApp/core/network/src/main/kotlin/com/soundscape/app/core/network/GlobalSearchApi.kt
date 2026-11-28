package com.soundscape.app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

@Serializable
data class GlobalSearchRequestDto(
    val query: String,
    val categories: List<String>? = null,
    val genreIds: List<Int>,
    val moodIds: List<Int>,
    val onlyInApp: Boolean,
    val limit: Int,
    val offset: Int,
    val includePublicMusicPins: Boolean = false,
)

@Serializable
data class SearchHitDto(
    val category: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val coverPath: String? = null,
    val availability: String? = null,
    val placeKind: String? = null,
)

@Serializable
data class GlobalSearchPageDto(val items: List<SearchHitDto>, val limit: Int, val offset: Int, val hasMore: Boolean)

@Serializable
data class SearchOptionDto(val id: Int, val name: String)

interface GlobalSearchApi {
    @Headers("X-Soundscape-Auth: Public")
    @POST("functions/v1/v1-search")
    suspend fun search(@Body request: GlobalSearchRequestDto): Response<GlobalSearchPageDto>

    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/genres?select=id,name&order=name")
    suspend fun genres(): Response<List<SearchOptionDto>>

    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/moods?select=id,name&order=name")
    suspend fun moods(): Response<List<SearchOptionDto>>
}
