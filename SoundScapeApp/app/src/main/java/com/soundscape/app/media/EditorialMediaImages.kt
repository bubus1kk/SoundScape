@file:Suppress("MagicNumber")

package com.soundscape.app.media

import android.content.Context
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Dimension
import com.soundscape.app.core.data.MediaBytes
import com.soundscape.app.core.data.RemoteMediaReader
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MediaImageRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EditorialMediaImages

class MediaImageException(val domainError: DomainError) : IOException("Media image request failed")

fun createEditorialMediaImageLoader(context: Context, reader: RemoteMediaReader): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache { null }
        .diskCache { null }
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .fetcherCoroutineContext(Dispatchers.IO)
        .components { add(MediaFetcherFactory(reader)) }
        .build()

private class MediaFetcherFactory(private val reader: RemoteMediaReader) : Fetcher.Factory<MediaImageRequest> {
    private val permits = Semaphore(4)

    override fun create(data: MediaImageRequest, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        permits.withPermit {
            if (!options.networkCachePolicy.readEnabled) throw MediaImageException(DomainError.Network)
            when (val result = reader.read(data)) {
                is DomainResult.Failure -> throw MediaImageException(result.error)
                is DomainResult.Success -> {
                    try {
                        decode(result.value, options)
                    } catch (_: IllegalArgumentException) {
                        throw MediaImageException(DomainError.Unexpected)
                    }
                }
            }
        }
    }
}

private suspend fun decode(image: MediaBytes, options: Options): FetchResult {
    currentCoroutineContext().ensureActive()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    require(bounds.outWidth.toLong() * bounds.outHeight <= 16L * 1024 * 1024)
    require(bounds.outMimeType == image.mimeType)
    val width = target(options.size.width)
    val height = target(options.size.height)
    var sample = 1
    while (bounds.outWidth / sample > width || bounds.outHeight / sample > height) sample *= 2
    currentCoroutineContext().ensureActive()
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, decodeOptions))
    var returned = false
    try {
        currentCoroutineContext().ensureActive()
        val result = ImageFetchResult(bitmap.asImage(), sample > 1, DataSource.NETWORK)
        returned = true
        return result
    } finally {
        if (!returned) bitmap.recycle()
    }
}

private fun target(dimension: Dimension): Int =
    ((dimension as? Dimension.Pixels)?.px ?: 1024).coerceIn(1, 2048)

@Module
@InstallIn(SingletonComponent::class)
object EditorialMediaImagesModule {
    @Provides
    @Singleton
    @EditorialMediaImages
    fun imageLoader(
        @ApplicationContext context: Context,
        reader: RemoteMediaReader,
    ): ImageLoader = createEditorialMediaImageLoader(context, reader)
}
