@file:Suppress("MagicNumber")

package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MediaImageRequest
import com.soundscape.app.core.network.MediaApi
import com.soundscape.app.core.network.MediaRequestDto
import dagger.Lazy
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MediaBytes internal constructor(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun toString(): String = "MediaBytes(size=${bytes.size}, mimeType=$mimeType)"
}

class RemoteMediaReader @Inject constructor(
    private val api: Lazy<MediaApi>,
    private val call: RemoteCall,
) {
    suspend fun read(request: MediaImageRequest): DomainResult<MediaBytes> = call.execute(
        request = {
            api.get().image(
                MediaRequestDto(request.objectType.name, request.objectId.toString(), request.photoId?.toString()),
                request.shareToken?.value,
            ).awaitImage()
        },
        map = { it },
    )
}

private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
private const val MAX_ERROR_BYTES = 16384L

private suspend fun Call<ResponseBody>.awaitImage(): Response<MediaBytes> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback<ResponseBody> {
            override fun onFailure(call: Call<ResponseBody>, error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (!continuation.isActive) {
                    response.body()?.close()
                    response.errorBody()?.close()
                    return
                }
                try {
                    val mapped = response.readImage {
                        if (!continuation.isActive) throw CancellationException("Media request cancelled")
                    }
                    continuation.resume(mapped) { _, discarded, _ -> discarded.errorBody()?.close() }
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } catch (error: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } catch (error: CancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

private fun Response<ResponseBody>.readImage(checkActive: () -> Unit): Response<MediaBytes> {
    if (!isSuccessful) {
        val original = errorBody()
        val contentType = original?.contentType()
        val bytes = original?.use {
            val source = it.source()
            source.request(MAX_ERROR_BYTES)
            source.buffer.clone().readByteArray(minOf(source.buffer.size, MAX_ERROR_BYTES))
        } ?: byteArrayOf()
        return Response.error(bytes.toResponseBody(contentType), raw())
    }
    val image = requireNotNull(body()).use { body ->
        require(code() == 200)
        checkActive()
        val type = body.contentType()
        val mime = type?.let { "${it.type}/${it.subtype}" }?.lowercase(Locale.ROOT)
        require(mime in setOf("image/png", "image/jpeg", "image/webp"))
        require(body.contentLength() in -1..MAX_IMAGE_BYTES)
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            checkActive()
            val remaining = MAX_IMAGE_BYTES + 1 - buffer.size
            val count = source.read(buffer, minOf(8192L, remaining))
            if (count == -1L) break
            require(buffer.size <= MAX_IMAGE_BYTES)
        }
        checkActive()
        require(buffer.size > 0)
        MediaBytes(buffer.readByteArray(), requireNotNull(mime))
    }
    return Response.success(code(), image)
}
