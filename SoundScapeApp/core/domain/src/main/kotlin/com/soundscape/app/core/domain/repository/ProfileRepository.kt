package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProfileSession : AuthRepository {
    val accountId: StateFlow<UUID?>
    suspend fun restore()
    suspend fun signOut()
}

interface ProfileRepository {
    val offline: Flow<Boolean>
    suspend fun own(id: UUID): DomainResult<UserProfile>
    suspend fun public(id: UUID): DomainResult<PublicProfile>
    suspend fun cachedOwn(id: UUID): UserProfile?
    suspend fun cachedPublic(id: UUID): PublicProfile?
    suspend fun rename(id: UUID, name: String): DomainResult<UserProfile>
    suspend fun publicMaterial(owner: UUID, id: UUID, playlist: Boolean): DomainResult<ProfileMaterial>
    fun isOffline(): Boolean
}
