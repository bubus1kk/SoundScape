package com.soundscape.app

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.data.NetworkErrorMapper
import com.soundscape.app.core.data.RemoteCall
import com.soundscape.app.core.data.RemotePlaybackSourceResolver
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.PlaybackSource
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.PlaybackSourceResolver
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.GlobalSearchApi
import com.soundscape.app.core.network.GlobalSearchRequestDto
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.ResolveTrackApi
import com.soundscape.app.navigation.SearchPlaybackViewModel
import com.soundscape.app.presentation.search.SearchPlayback
import dagger.Lazy
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AudiusPlaybackTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun searchesResolvesPlaysPausesResumesAndRejectsUnavailable() = runBlocking {
        val origin = args.getString("audiusE2eUrl").orEmpty()
        val key = args.getString("audiusE2ePublishableKey").orEmpty()
        assumeTrue("Configure an isolated backend using audiusE2eUrl and audiusE2ePublishableKey",
            origin.isNotBlank() && key.isNotBlank())
        val query = args.getString("audiusE2eQuery") ?: "Xsetra - Fragments"
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("apikey", key).build())
        }
        args.getString("audiusE2eCaPemBase64")?.let { encoded ->
            val pem = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).decodeToString()
            val tls = HandshakeCertificates.Builder().addPlatformTrustedCertificates()
                .addTrustedCertificate(pem.decodeCertificatePem()).build()
            client.sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
        }
        val retrofit = Retrofit.Builder().baseUrl(origin.trimEnd('/') + "/").client(client.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        val search = retrofit.create(GlobalSearchApi::class.java)
        val request = GlobalSearchRequestDto(query, listOf("TRACK"), emptyList(), emptyList(),
            onlyInApp = true, limit = 50, offset = 0)
        val row = checkNotNull(search.search(request).body()).items.first { it.title.equals(query, true) }
        assertTrue(!row.subtitle.isNullOrBlank())
        assertEquals("IN_APP", row.availability)
        val id = UUID.fromString(row.id)
        val all = checkNotNull(search.search(request.copy(categories = null)).body())
        assertEquals(1, all.items.count { it.category == "TRACK" && it.id == row.id })
        val hit = SearchHit(SearchCategory.TRACK, id, row.title, row.subtitle, null, TrackAvailability.IN_APP)

        val call = RemoteCall(NetworkConfig(origin, key), ConnectivityChecker { false }, NetworkErrorMapper(json))
        val resolver = RemotePlaybackSourceResolver(Lazy { retrofit.create(ResolveTrackApi::class.java) }, call)
        val source = (resolver.resolve(id) as DomainResult.Success).value as PlaybackSource.InApp
        assertEquals(id, source.trackId)
        assertFalse(source.cacheAllowed)
        assertFalse(source.downloadAllowed)

        val missing = args.getString("audiusE2eUnavailableId")?.let(UUID::fromString) ?: UUID.randomUUID()
        exercisePlayback(hit, missing, resolver)
    }

    @Test
    fun media3AdvancesPausesResumesAndStopsWithAnOfflineAudioFixture() {
        val hit = SearchHit(
            SearchCategory.TRACK, UUID.randomUUID(), "Local audio fixture", null, null, TrackAvailability.IN_APP,
        )
        val audio = File.createTempFile("soundscape-media3-", ".wav", test.targetContext.cacheDir)
        try {
            writePcmFixture(audio)
            val resolver = object : PlaybackSourceResolver {
                override suspend fun resolve(trackId: UUID): DomainResult<PlaybackSource> =
                    if (trackId == hit.id) DomainResult.Success(PlaybackSource.InApp(trackId, audio.toURI()))
                    else DomainResult.Failure(DomainError.NotFound)
            }
            exercisePlayback(hit, UUID.randomUUID(), resolver)
        } finally {
            audio.delete()
        }
    }

    private fun exercisePlayback(hit: SearchHit, missing: UUID, resolver: PlaybackSourceResolver) {
        val model = SearchPlaybackViewModel(test.targetContext, resolver)
        val store = ViewModelStore()
        test.runOnMainSync { store.put("audius", model) }
        try {
            val unexpectedExternal: (java.net.URI) -> Unit = { error("Unexpected external action") }
            test.runOnMainSync { model.toggle(hit, unexpectedExternal) }
            waitFor("initial playback position advances") {
                model.state.value?.second == SearchPlayback.Playing && model.player.currentPosition > 750
            }
            test.runOnMainSync { model.toggle(hit, unexpectedExternal) }
            waitFor("paused") { model.state.value?.second == SearchPlayback.Ready && !model.player.isPlaying }
            // The audio renderer can publish its final queued position just after isPlaying changes.
            SystemClock.sleep(250)
            var pausedAt = 0L
            test.runOnMainSync { pausedAt = model.player.currentPosition }
            SystemClock.sleep(500)
            test.runOnMainSync {
                assertFalse(model.player.isPlaying)
                assertTrue("Position advanced while paused", abs(pausedAt - model.player.currentPosition) <= 20)
            }
            test.runOnMainSync { model.toggle(hit, unexpectedExternal) }
            waitFor("resumed playback position advances") {
                model.player.isPlaying && model.player.currentPosition > pausedAt + 500
            }
            test.runOnMainSync { model.toggle(hit.copy(id = missing), unexpectedExternal) }
            waitFor("unavailable track rejected") {
                model.state.value == (missing to SearchPlayback.Failed) && !model.player.isPlaying
            }
        } finally { test.runOnMainSync { store.clear() } }
    }

    private fun writePcmFixture(file: File) {
        val sampleRate = 16_000
        val bytesPerSample = 2
        val dataSize = sampleRate * bytesPerSample * 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".encodeToByteArray())
            putInt(dataSize + 36)
            put("WAVEfmt ".encodeToByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * bytesPerSample)
            putShort(bytesPerSample.toShort())
            putShort((bytesPerSample * Byte.SIZE_BITS).toShort())
            put("data".encodeToByteArray())
            putInt(dataSize)
        }
        RandomAccessFile(file, "rw").use {
            it.write(header.array())
            it.setLength(header.capacity().toLong() + dataSize)
        }
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            test.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $label")
    }
}
