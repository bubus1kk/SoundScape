package com.soundscape.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class NetworkConfig(val url: String, val publishableKey: String, val debug: Boolean = false) {
    val ready: Boolean get() {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null &&
            publishableKey.startsWith("sb_publishable_") && publishableKey.none { it.isWhitespace() }
    }
    override fun toString(): String = "NetworkConfig(ready=$ready)"
}

fun interface AccessTokenProvider {
    fun currentAccessToken(): String?
}

fun interface ConnectivityChecker {
    fun isOffline(): Boolean
}
