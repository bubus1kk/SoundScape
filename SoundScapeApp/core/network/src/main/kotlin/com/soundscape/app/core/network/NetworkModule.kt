@file:Suppress("MagicNumber")

package com.soundscape.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ApiCredentialsInterceptor(
    private val config: NetworkConfig, private val tokens: AccessTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        check(config.ready) { "Network configuration unavailable" }
        val request = chain.request()
        val origin = config.url.toHttpUrl()
        check(request.url.host == origin.host && request.url.port == origin.port && request.url.scheme == origin.scheme)
        val policy = RequestAuthPolicy.valueOf(checkNotNull(request.header("X-Soundscape-Auth")) {
            "Request authentication policy required"
        })
        val requestCredential = request.tag(SessionCredential::class.java)
        val token = when (policy) {
            RequestAuthPolicy.Public -> null
            RequestAuthPolicy.Authenticated -> checkNotNull(
                (requestCredential?.token ?: tokens.currentAccessToken())?.takeIf { it.isNotBlank() },
            ) {
                "Authentication required"
            }
            RequestAuthPolicy.OptionalUser -> tokens.currentAccessToken()
        }
        val builder = request.newBuilder().header("apikey", config.publishableKey).removeHeader("Authorization")
        builder.removeHeader("X-Soundscape-Guest")
        builder.removeHeader("X-Soundscape-Auth")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return chain.proceed(builder.build())
    }
}
class SafeNetworkLoggingInterceptor(private val sink: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val start = System.nanoTime()
        return try {
            val response = chain.proceed(chain.request())
            sink("HTTP ${response.code} ${(System.nanoTime() - start) / 1_000_000}ms")
            response
        } catch (error: IOException) {
            sink("HTTP transport failure")
            throw error
        }
    }
}
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun json(): Json = Json { ignoreUnknownKeys = true }
    @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityChecker =
        object : ConnectivityChecker {
            private val manager = context.getSystemService(ConnectivityManager::class.java)
            override fun isOffline() = manager.activeNetwork == null
            override fun observeOffline() = callbackFlow {
                val listener = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(false) }
                    override fun onLost(network: Network) { trySend(isOffline()) }
                }
                manager.registerDefaultNetworkCallback(listener)
                trySend(isOffline())
                awaitClose { manager.unregisterNetworkCallback(listener) }
            }.distinctUntilChanged()
        }
    @Provides @Singleton fun client(config: NetworkConfig, tokens: AccessTokenProvider): OkHttpClient =
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor(ApiCredentialsInterceptor(config, tokens))
            .apply { if (config.debug) addInterceptor(SafeNetworkLoggingInterceptor { Log.d("Network", it) }) }
            .build()
    @Provides @Singleton fun retrofit(config: NetworkConfig, client: OkHttpClient, json: Json): Retrofit {
        check(config.ready) { "Network configuration unavailable" }
        return Retrofit.Builder().baseUrl(config.url).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }
    @Provides fun places(retrofit: Retrofit): PlacesApi = retrofit.create(PlacesApi::class.java)
    @Provides fun globalSearch(retrofit: Retrofit): GlobalSearchApi = retrofit.create(GlobalSearchApi::class.java)
    @Provides fun tracks(retrofit: Retrofit): TracksApi = retrofit.create(TracksApi::class.java)
    @Provides fun playlists(retrofit: Retrofit): PlaylistsApi = retrofit.create(PlaylistsApi::class.java)
    @Provides fun resolveTrack(retrofit: Retrofit): ResolveTrackApi = retrofit.create(ResolveTrackApi::class.java)
    @Provides fun media(retrofit: Retrofit): MediaApi = retrofit.create(MediaApi::class.java)
    @Provides fun profiles(retrofit: Retrofit): ProfileApi = retrofit.create(ProfileApi::class.java)
    @Provides fun sessions(retrofit: Retrofit): SessionApi = retrofit.create(SessionApi::class.java)
}
