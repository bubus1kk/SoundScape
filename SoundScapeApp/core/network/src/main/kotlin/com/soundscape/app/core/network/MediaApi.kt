package com.soundscape.app.core.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Streaming

@Serializable
data class MediaRequestDto(
    val objectType: String,
    val objectId: String,
    val photoId: String? = null,
)

interface MediaApi {
    @Streaming
    @Headers("X-Soundscape-Auth: OptionalUser", "Cache-Control: no-store")
    @POST("functions/v1/v1-media")
    fun image(
        @Body request: MediaRequestDto,
        @Header("X-SoundScape-Share-Token") shareToken: String? = null,
    ): Call<ResponseBody>
}
