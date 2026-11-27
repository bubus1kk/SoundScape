@file:Suppress("DEPRECATION", "MagicNumber")

package com.soundscape.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.data.NetworkErrorMapper
import com.soundscape.app.core.data.RemoteCall
import com.soundscape.app.core.data.RemoteMediaReader
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.MediaImageRequest
import com.soundscape.app.core.domain.model.MediaObjectType
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.MediaApi
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.NetworkModule
import com.soundscape.app.media.EditorialMediaImagesModule
import com.soundscape.app.media.CityPhoto
import com.soundscape.app.media.MediaImageException
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeUiState
import dagger.Lazy
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class EditorialMediaImagesTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun decodesFormatsAndReloadsWithoutCacheOrTemporaryFiles() = runBlocking {
        withLoader { context, server, loader ->
            val before = cacheFiles(context)
            assertNull(loader.memoryCache)
            assertNull(loader.diskCache)
            val formats = listOf(
                Bitmap.CompressFormat.PNG to "image/png",
                Bitmap.CompressFormat.JPEG to "image/jpeg",
                Bitmap.CompressFormat.WEBP to "image/webp",
            )
            for ((format, mime) in formats) {
                server.enqueue(imageResponse(image(format, Color.RED), mime))
                assertTrue(loader.execute(imageRequest(context, 1)) is SuccessResult)
            }
            for ((id, color) in listOf(1 to Color.RED, 2 to Color.BLUE, 1 to Color.GREEN)) {
                server.enqueue(imageResponse(image(Bitmap.CompressFormat.PNG, color), "image/png"))
                val result = loader.execute(imageRequest(context, id)) as SuccessResult
                assertEquals(color, (result.image as BitmapImage).bitmap.getPixel(0, 0))
            }
            assertEquals(6, server.requestCount)
            val bodies = mutableListOf<String>()
            repeat(6) {
                val request = server.takeRequest()
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Soundscape-Auth"))
                bodies += request.body.readUtf8()
            }
            assertTrue(bodies[3].contains(UUID(0, 1).toString()))
            assertTrue(bodies[4].contains(UUID(0, 2).toString()))
            assertTrue(bodies[5].contains(UUID(0, 1).toString()))
            assertEquals(emptySet<String>(), cacheFiles(context) - before)
        }
    }

    @Test
    fun placeErrorsStayTypedForNotFoundTimeoutAndDamagedPhoto() = runBlocking {
        withLoader { context, server, loader ->
            val responses = listOf(
                MockResponse().setResponseCode(404) to DomainError.NotFound,
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) to DomainError.Timeout,
                imageResponse(byteArrayOf(1, 2, 3), "image/png") to DomainError.Unexpected,
            )
            for ((response, expected) in responses) {
                server.enqueue(response)
                val request = imageRequest(context, 1).newBuilder()
                    .data(MediaImageRequest(MediaObjectType.PLACE, UUID(0, 1))).build()
                val result = loader.execute(request) as ErrorResult
                assertEquals(expected, (result.throwable as MediaImageException).domainError)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun cityWithoutPathSkipsHttpAndNotFoundLeavesTheSamePlaceholderAndTitle() = runBlocking {
        withLoader { _, server, loader ->
            val noCover = city(1)
            val cities = mutableStateOf(listOf(noCover))
            compose.setContent {
                SoundScapeTheme {
                    HomeScreen(HomeUiState.Content(cities.value), {}, {}) { id, modifier ->
                        CityPhoto(id, loader, modifier)
                    }
                }
            }
            val placeholder = photoPixel(noCover.id)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(404))
            compose.runOnIdle { cities.value = listOf(noCover.copy(coverPath = "places/photo.webp")) }
            compose.waitUntil(5_000) { server.requestCount == 1 }
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/functions/v1/v1-media", request.path)
            assertEquals(
                NetworkModule.json().parseToJsonElement(
                    """{"objectType":"PLACE","objectId":"${noCover.id}"}""",
                ),
                NetworkModule.json().parseToJsonElement(request.body.readUtf8()),
            )
            assertEquals(placeholder, photoPixel(noCover.id))
            compose.onNodeWithText(noCover.title).assertIsDisplayed()
        }
    }

    @Test
    fun scrollingBetweenFirstAndTwentiethCityKeepsPhotosMatchedById() = runBlocking {
        withLoader { _, server, loader ->
            val cities = (1..20).map(::city).map { it.copy(coverPath = "places/${it.id}.webp") }
            val red = image(Bitmap.CompressFormat.PNG, Color.RED)
            val blue = image(Bitmap.CompressFormat.PNG, Color.BLUE)
            val first = cities.first()
            val last = cities.last()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val json = NetworkModule.json().parseToJsonElement(request.body.readUtf8()).jsonObject
                    return when (json["objectId"]?.jsonPrimitive?.content) {
                        first.id.toString() -> imageResponse(red, "image/png").setBodyDelay(250, TimeUnit.MILLISECONDS)
                        last.id.toString() -> imageResponse(blue, "image/png")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            compose.setContent {
                SoundScapeTheme {
                    HomeScreen(HomeUiState.Content(cities), {}, {}) { id, modifier ->
                        CityPhoto(id, loader, modifier)
                    }
                }
            }
            val row = compose.onNodeWithTag("home-city-list")
            row.performScrollToIndex(cities.lastIndex)
            row.performScrollToIndex(0)
            row.performScrollToIndex(cities.lastIndex)
            compose.waitUntil(5_000) { photoPixel(last.id) == Color.BLUE }
            compose.onNodeWithText(last.title).assertIsDisplayed()
            row.performScrollToIndex(0)
            compose.waitUntil(5_000) { photoPixel(first.id) == Color.RED }
            compose.onNodeWithText(first.title).assertIsDisplayed()
        }
    }

    private fun city(index: Int) = MapPlace(
        UUID(0, index.toLong()),
        MapPlaceKind.CITY,
        "Город $index",
        (GeoPoint.create(0.0, 0.0) as DomainResult.Success).value,
    )

    private fun photoPixel(id: UUID): Int {
        val pixels = compose.onNodeWithTag("home-city-image-$id", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        return pixels[pixels.width / 3, pixels.height / 3].toArgb()
    }

    private suspend fun withLoader(block: suspend (Context, MockWebServer, ImageLoader) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val origin = server.url("/").newBuilder().host("localhost").build()
            val config = NetworkConfig(origin.toString(), "sb_publishable_test")
            val client = NetworkModule.client(config, AccessTokenProvider { null }).newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .readTimeout(2, TimeUnit.SECONDS).build()
            val json = NetworkModule.json()
            val api = Retrofit.Builder().baseUrl(origin).client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(MediaApi::class.java)
            val reader = RemoteMediaReader(Lazy { api }, RemoteCall(
                config, ConnectivityChecker { false }, NetworkErrorMapper(json),
            ))
            val loader = EditorialMediaImagesModule.imageLoader(context, reader)
            try {
                block(context, server, loader)
            } finally {
                loader.shutdown()
            }
        }
    }

    private fun cacheFiles(context: Context): Set<String> = context.cacheDir.walkTopDown()
        .filter { it.isFile }.map { it.canonicalPath }.toSet()

    private fun imageRequest(context: Context, id: Int): ImageRequest = ImageRequest.Builder(context)
        .data(MediaImageRequest(MediaObjectType.PLAYLIST, UUID(0, id.toLong())))
        .size(32)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    private fun image(format: Bitmap.CompressFormat, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use {
                check(bitmap.compress(format, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageResponse(bytes: ByteArray, mime: String): MockResponse = MockResponse()
        .setHeader("Content-Type", mime).setHeader("Cache-Control", "no-store, max-age=0")
        .setBody(Buffer().write(bytes))
}
