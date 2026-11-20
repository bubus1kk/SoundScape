package com.soundscape.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Tag

class SessionCredential(val token: String)

@Serializable
class PasswordGrant(val email: String, val password: String)

@Serializable
class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)

@Serializable
class AuthTokens(
    @SerialName("access_token") val access: String,
    @SerialName("refresh_token") val refresh: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: AuthUser,
)

@Serializable
class AuthUser(val id: String)

interface SessionApi {
    @Headers("X-Soundscape-Auth: Public")
    @POST("auth/v1/token?grant_type=password")
    suspend fun signIn(@Body credentials: PasswordGrant): Response<AuthTokens>

    @Headers("X-Soundscape-Auth: Public")
    @POST("auth/v1/token?grant_type=refresh_token")
    suspend fun refresh(@Body grant: RefreshGrant): Response<AuthTokens>

    @Headers("X-Soundscape-Auth: Authenticated")
    @POST("auth/v1/logout?scope=local")
    suspend fun logout(@Tag credential: SessionCredential): Response<Unit>
}

@Serializable
data class ProfileDto(val id: String, val username: String)

@Serializable
data class ProfileRenameDto(val username: String)

@Serializable
data class ProfileTrackCountDto(val count: Int)

@Serializable
data class ProfileMaterialDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val title: String,
    val description: String? = null,
    val visibility: String,
    @SerialName("playlist_tracks") val playlistTracks: List<ProfileTrackCountDto> = emptyList(),
    @SerialName("pin_tracks") val pinTracks: List<ProfileTrackCountDto> = emptyList(),
)

interface ProfileApi {
    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/profiles?select=id,username")
    suspend fun publicProfile(@Query("id") id: String): Response<List<ProfileDto>>

    @Headers("X-Soundscape-Auth: Authenticated")
    @GET("rest/v1/profiles?select=id,username")
    suspend fun ownProfile(@Query("id") id: String, @Tag credential: SessionCredential): Response<List<ProfileDto>>

    @Headers("X-Soundscape-Auth: Authenticated", "Prefer: return=representation")
    @PATCH("rest/v1/profiles?select=id,username")
    suspend fun rename(
        @Query("id") id: String, @Body profile: ProfileRenameDto, @Tag credential: SessionCredential,
    ): Response<List<ProfileDto>>

    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/playlists?select=id,owner_id,title,description,visibility,playlist_tracks(count)" +
        "&visibility=eq.PUBLIC&order=id")
    suspend fun playlists(
        @Query("owner_id") owner: String, @Query("offset") offset: Int, @Query("limit") limit: Int,
        @Query("id") id: String? = null,
    ): Response<List<ProfileMaterialDto>>

    @Headers("X-Soundscape-Auth: Public")
    @GET("rest/v1/music_pins?select=id,owner_id,title,description,visibility,pin_tracks(count)" +
        "&visibility=eq.PUBLIC&order=id")
    suspend fun pins(
        @Query("owner_id") owner: String, @Query("offset") offset: Int, @Query("limit") limit: Int,
        @Query("id") id: String? = null,
    ): Response<List<ProfileMaterialDto>>
}
