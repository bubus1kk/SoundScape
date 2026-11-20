package com.soundscape.app.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@Serializable
data class ResolveTrackRequestDto(
    val trackId: String,
)

@Serializable
@Suppress("LongParameterList")
data class ResolvedSourceDto(
    val trackId: String,
    val availability: String,
    val provider: String,
    val cacheAllowed: Boolean,
    val downloadAllowed: Boolean,
    val streamUrl: String? = null,
    val streamExpiresAt: String? = null,
    val externalUrl: String? = null,
)

interface ResolveTrackApi {
    @Headers("X-Soundscape-Auth: OptionalUser")
    @POST("functions/v1/v1-resolve-track")
    suspend fun resolve(
        @Body request: ResolveTrackRequestDto,
    ): Response<ResolvedSourceDto>
}
