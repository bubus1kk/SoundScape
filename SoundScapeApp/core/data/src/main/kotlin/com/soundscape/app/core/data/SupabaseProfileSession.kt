package com.soundscape.app.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.network.AccessTokenProvider
import com.soundscape.app.core.network.AuthTokens
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PasswordGrant
import com.soundscape.app.core.network.ProfileApi
import com.soundscape.app.core.network.RefreshGrant
import com.soundscape.app.core.network.SessionApi
import com.soundscape.app.core.network.SessionCredential
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val REFRESH_MARGIN_SECONDS = 60
private const val KEY_ALIAS = "soundscape.profile.session"
private const val KEY_STORE = "AndroidKeyStore"
private const val IV_BYTES = 12
private const val TAG_BITS = 128

internal class StoredSession(val id: UUID, val access: String, val refresh: String, val expiresAt: Long) {
    fun usable() = expiresAt > nowSeconds() + REFRESH_MARGIN_SECONDS
}

private fun nowSeconds() = java.time.Instant.now().epochSecond

@Singleton
class SupabaseProfileSession @Inject constructor(
    private val api: Lazy<SessionApi>,
    private val profileApi: Lazy<ProfileApi>,
    private val remote: RemoteCall,
    private val store: EncryptedSessionStore,
) : ProfileSession, AccessTokenProvider {
    private val mutex = Mutex()
    private val account = MutableStateFlow<UUID?>(null)
    override val accountId = account.asStateFlow()
    @Volatile private var current: StoredSession? = null
    private var restored = false

    override suspend fun restore() {
        mutex.withLock {
            if (!restored) {
                current = withContext(Dispatchers.IO) { store.read() }
                account.value = current?.id
                restored = true
            }
        }
    }

    override fun currentAccessToken(): String? = current?.takeIf { it.usable() }?.access

    override suspend fun currentUser(): DomainResult<UserProfile?> {
        restore()
        val id = account.value ?: return DomainResult.Success(null)
        return readUser(id)
    }

    override suspend fun signIn(email: String, password: String): DomainResult<UserProfile> {
        restore()
        val result = mutex.withLock {
            val signedIn = remote.execute({ api.get().signIn(PasswordGrant(email, password)) }, ::toSession)
            if (signedIn is DomainResult.Success) install(signedIn.value)
            signedIn
        }
        return when (result) {
            is DomainResult.Success -> readUser(result.value.id)
            is DomainResult.Failure -> result
        }
    }

    internal suspend fun readUser(id: UUID) = authorized(id) { credential ->
        val result = remote.execute({ profileApi.get().ownProfile("eq.$id", credential) }, ::profileSessionError) {
            it.singleOrNull()?.takeIf { row -> UUID.fromString(row.id) == id }?.let { row ->
                UserProfile(id, row.username)
            }
        }
        when (result) {
            is DomainResult.Success -> result.value?.let { DomainResult.Success(it) }
                ?: DomainResult.Failure(DomainError.NotFound)
            is DomainResult.Failure -> result
        }
    }

    override suspend fun signOut() {
        restore()
        val previous = mutex.withLock {
            val session = current
            install(null)
            session
        }
        if (previous != null) {
            remote.execute({ api.get().logout(SessionCredential(previous.access)) }) { Unit }
        }
    }

    internal suspend fun <T> authorizedOnce(
        request: suspend (SessionCredential) -> DomainResult<T>,
    ): DomainResult<T> {
        restore()
        val id = account.value ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return when (val value = credential(id)) {
            is DomainResult.Success -> {
                val token = value.value
                val result = request(SessionCredential(token))
                if (result == DomainResult.Failure(DomainError.Unauthenticated)) {
                    mutex.withLock {
                        if (current?.id == id && current?.access == token) install(null)
                    }
                }
                if (account.value == id) result else DomainResult.Failure(DomainError.Unauthenticated)
            }
            is DomainResult.Failure -> value
        }
    }

    internal suspend fun <T> authorized(
        id: UUID, request: suspend (SessionCredential) -> DomainResult<T>,
    ): DomainResult<T> {
        val token = when (val result = credential(id)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val first = request(SessionCredential(token))
        val result = if (first == DomainResult.Failure(DomainError.Unauthenticated)) {
            when (val retry = credential(id, token)) {
                is DomainResult.Success -> {
                    val response = request(SessionCredential(retry.value))
                    if (response == DomainResult.Failure(DomainError.Unauthenticated)) {
                        mutex.withLock {
                            if (current?.access == retry.value) install(null)
                        }
                    }
                    response
                }
                is DomainResult.Failure -> retry
            }
        } else first
        return if (account.value == id) result else DomainResult.Failure(DomainError.Unauthenticated)
    }

    private suspend fun credential(id: UUID, rejected: String? = null): DomainResult<String> = mutex.withLock {
        val session = current?.takeIf { it.id == id }
            ?: return@withLock DomainResult.Failure(DomainError.Unauthenticated)
        if (session.usable() && session.access != rejected) return@withLock DomainResult.Success(session.access)
        val refreshed = remote.execute(
            { api.get().refresh(RefreshGrant(session.refresh)) },
            { status -> if (status in rejectedRefreshStatuses) DomainError.Unauthenticated else null },
        ) { toSession(it).also { newSession -> require(newSession.id == id) } }
        when (refreshed) {
            is DomainResult.Success -> {
                install(refreshed.value)
                DomainResult.Success(refreshed.value.access)
            }
            is DomainResult.Failure -> {
                if (refreshed.error == DomainError.Unauthenticated) install(null)
                refreshed
            }
        }
    }

    private suspend fun install(session: StoredSession?) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { store.write(session) }.onFailure { store.write(null) }
            current = session
            account.value = session?.id
        }
    }

    private fun toSession(tokens: AuthTokens): StoredSession {
        require(tokens.access.isNotBlank() && tokens.refresh.isNotBlank() && tokens.expiresIn > 0)
        return StoredSession(
            UUID.fromString(tokens.user.id), tokens.access, tokens.refresh, nowSeconds() + tokens.expiresIn,
        )
    }
}

private const val HTTP_UNAUTHORIZED = 401

@Suppress("MagicNumber")
private val rejectedRefreshStatuses = setOf(400, HTTP_UNAUTHORIZED, 403)

internal fun profileSessionError(status: Int): DomainError? =
    if (status == HTTP_UNAUTHORIZED) DomainError.Unauthenticated else null

class EncryptedSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val config: NetworkConfig,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "soundscape-session"))

    internal fun read(): StoredSession? = runCatching {
        val bytes = file.readFully()
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        DataInputStream(ByteArrayInputStream(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))).use {
            require(it.readUTF() == config.url.trimEnd('/'))
            StoredSession(UUID.fromString(it.readUTF()), it.readUTF(), it.readUTF(), it.readLong())
        }
    }.getOrNull()

    internal fun write(session: StoredSession?) {
        if (session == null) {
            file.delete()
            return
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeUTF(config.url.trimEnd('/'))
            it.writeUTF(session.id.toString())
            it.writeUTF(session.access)
            it.writeUTF(session.refresh)
            it.writeLong(session.expiresAt)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(cipher.doFinal(output.toByteArray()))
            file.finishWrite(stream)
        } catch (error: java.io.IOException) {
            file.failWrite(stream)
            throw error
        } catch (error: java.security.GeneralSecurityException) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
    }
}
