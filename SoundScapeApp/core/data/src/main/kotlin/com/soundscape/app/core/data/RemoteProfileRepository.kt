package com.soundscape.app.core.data

import android.content.Context
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.model.profileNameError
import com.soundscape.app.core.domain.repository.ProfileRepository
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.ProfileApi
import com.soundscape.app.core.network.ProfileDto
import com.soundscape.app.core.network.ProfileMaterialDto
import com.soundscape.app.core.network.ProfileRenameDto
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import retrofit2.Response

private const val PAGE_SIZE = 100
private const val CACHED_PROFILES = 16

@Singleton
@Suppress("TooManyFunctions")
class RemoteProfileRepository @Inject constructor(
    private val api: Lazy<ProfileApi>,
    private val remote: RemoteCall,
    private val session: SupabaseProfileSession,
    private val connectivity: ConnectivityChecker,
    private val json: Json,
    @ApplicationContext context: Context,
) : ProfileRepository {
    private val preferences = context.getSharedPreferences("profile_snapshots", Context.MODE_PRIVATE)

    override fun isOffline() = connectivity.isOffline()

    override val offline = connectivity.observeOffline()

    override suspend fun own(id: UUID): DomainResult<UserProfile> {
        session.restore()
        return session.readUser(id).also { result ->
            if (result is DomainResult.Success && session.accountId.value == id) {
                save("own:$id", ProfileDto(id.toString(), result.value.displayName), emptyList(), emptyList())
            }
        }
    }

    override suspend fun cachedOwn(id: UUID): UserProfile? {
        if (session.accountId.value != id) return null
        return snapshot("own:$id")?.profile?.toDomain(id)?.takeIf { session.accountId.value == id }
    }

    @Suppress("ReturnCount")
    override suspend fun public(id: UUID): DomainResult<PublicProfile> {
        val result = remote.execute({ api.get().publicProfile("eq.$id") }) { it.singleOrNull() }
        if (result is DomainResult.Failure) {
            if (result.error == DomainError.Forbidden || result.error == DomainError.NotFound) remove("public:$id")
            return result
        }
        val dto = (result as DomainResult.Success).value
        val user = dto?.toDomain(id)
        if (user == null) {
            remove("public:$id")
            return DomainResult.Failure(DomainError.NotFound)
        }
        return coroutineScope {
            val playlists = async { materials(id, playlist = true) }
            val pins = async { materials(id, playlist = false) }
            val playlistResult = playlists.await()
            val pinResult = pins.await()
            if (playlistResult is DomainResult.Failure) return@coroutineScope playlistResult
            if (pinResult is DomainResult.Failure) return@coroutineScope pinResult
            val playlistRows = (playlistResult as DomainResult.Success).value
            val pinRows = (pinResult as DomainResult.Success).value
            save("public:$id", dto, playlistRows, pinRows)
            DomainResult.Success(
                PublicProfile(user, publicMaterials(id, playlistRows, true), publicMaterials(id, pinRows, false)),
            )
        }
    }

    override suspend fun cachedPublic(id: UUID): PublicProfile? = snapshot("public:$id")?.let {
        it.profile.toDomain(id)?.let { user ->
            PublicProfile(user, publicMaterials(id, it.playlists, true), publicMaterials(id, it.pins, false))
        }
    }

    override suspend fun rename(id: UUID, name: String): DomainResult<UserProfile> {
        profileNameError(name)?.let { return DomainResult.Failure(it) }
        session.restore()
        return session.authorized(id) { credential ->
            remote.execute(
                { api.get().rename("eq.$id", ProfileRenameDto(name.trim()), credential) }, ::profileSessionError,
            ) {
                it.singleOrNull()?.toDomain(id)
            }.let { result ->
                when (result) {
                    is DomainResult.Success -> result.value?.let { DomainResult.Success(it) }
                        ?: DomainResult.Failure(DomainError.NotFound)
                    is DomainResult.Failure -> result
                }
            }
        }.also { result ->
            if (result is DomainResult.Success && session.accountId.value == id) {
                val updated = ProfileDto(id.toString(), result.value.displayName)
                snapshot("public:$id")?.let { save("public:$id", updated, it.playlists, it.pins) }
                save("own:$id", updated, emptyList(), emptyList())
            }
        }
    }

    override suspend fun publicMaterial(owner: UUID, id: UUID, playlist: Boolean): DomainResult<ProfileMaterial> {
        val result = remote.execute({ fetch(owner, playlist, 0, 1, id) }) {
            publicMaterials(owner, it, playlist).singleOrNull()?.takeIf { item -> item.id == id }
        }
        return when (result) {
            is DomainResult.Success -> result.value?.let { DomainResult.Success(it) }
                ?: DomainResult.Failure(DomainError.NotFound)
            is DomainResult.Failure -> result
        }
    }

    private suspend fun materials(id: UUID, playlist: Boolean): DomainResult<List<ProfileMaterialDto>> {
        val rows = mutableListOf<ProfileMaterialDto>()
        var offset = 0
        while (true) {
            when (val result = remote.execute({ fetch(id, playlist, offset, PAGE_SIZE) }) { it }) {
                is DomainResult.Failure -> return result
                is DomainResult.Success -> {
                    rows += result.value.filter { it.ownerId == id.toString() && it.visibility == "PUBLIC" }
                    offset += result.value.size
                    if (result.value.size < PAGE_SIZE) return DomainResult.Success(rows)
                }
            }
        }
    }

    private suspend fun fetch(
        owner: UUID, playlist: Boolean, offset: Int, limit: Int, item: UUID? = null,
    ): Response<List<ProfileMaterialDto>> {
        val filter = "eq.$owner"
        val itemFilter = item?.let { "eq.$it" }
        return if (playlist) api.get().playlists(filter, offset, limit, itemFilter)
        else api.get().pins(filter, offset, limit, itemFilter)
    }

    private suspend fun snapshot(key: String): ProfileSnapshot? = withContext(Dispatchers.IO) {
        preferences.getString(key, null)?.let { raw ->
            runCatching {
                val parts = json.parseToJsonElement(raw) as JsonArray
                ProfileSnapshot(
                    json.decodeFromJsonElement(ProfileDto.serializer(), parts[0]),
                    json.decodeFromJsonElement(materialList, parts[1]),
                    json.decodeFromJsonElement(materialList, parts[2]),
                )
            }.getOrNull()
        }
    }

    private suspend fun save(
        key: String, profile: ProfileDto, playlists: List<ProfileMaterialDto>, pins: List<ProfileMaterialDto>,
    ) = withContext(Dispatchers.IO) {
        synchronized(preferences) {
            val keys = preferences.getString("order", "").orEmpty().split(',')
                .filter { it.isNotEmpty() && it != key } + key
            val data = JsonArray(listOf(
                json.encodeToJsonElement(ProfileDto.serializer(), profile),
                json.encodeToJsonElement(materialList, playlists),
                json.encodeToJsonElement(materialList, pins),
            ))
            val editor = preferences.edit()
            keys.dropLast(CACHED_PROFILES).forEach { editor.remove(it) }
            editor.putString(key, data.toString())
                .putString("order", keys.takeLast(CACHED_PROFILES).joinToString(",")).apply()
        }
    }

    private suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        synchronized(preferences) { preferences.edit().remove(key).apply() }
    }
}

private val materialList = ListSerializer(ProfileMaterialDto.serializer())
private data class ProfileSnapshot(
    val profile: ProfileDto, val playlists: List<ProfileMaterialDto>, val pins: List<ProfileMaterialDto>,
)

private fun ProfileDto.toDomain(id: UUID): UserProfile? =
    if (this.id == id.toString() && username.isNotBlank()) UserProfile(id, username) else null

internal fun publicMaterials(owner: UUID, rows: List<ProfileMaterialDto>, playlist: Boolean): List<ProfileMaterial> =
    rows.mapNotNull { item ->
        if (item.ownerId != owner.toString() || item.visibility != "PUBLIC" || item.title.isBlank()) {
            return@mapNotNull null
        }
        val id = runCatching { UUID.fromString(item.id) }.getOrNull()?.takeIf { it.toString() == item.id }
            ?: return@mapNotNull null
        val count = (if (playlist) item.playlistTracks else item.pinTracks).firstOrNull()?.count ?: 0
        ProfileMaterial(id, item.title, count.coerceAtLeast(0), item.description)
    }.distinctBy { it.id }
