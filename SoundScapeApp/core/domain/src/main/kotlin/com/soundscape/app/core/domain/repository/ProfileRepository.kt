package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Exposes the session state required by the current profile flow. */
interface ProfileSession : AuthRepository {
    val accountId: StateFlow<UUID?>
    /** Restores the session from the configured account storage. */
    suspend fun restore()
    /** Ends the current local profile session. */
    suspend fun signOut()
}

/** Reads and edits profile data used by the current profile flow. */
interface ProfileRepository {
    val offline: Flow<Boolean>
    /** Loads the authenticated user's own profile. */
    suspend fun own(id: UUID): DomainResult<UserProfile>
    /** Loads a user's profile in the public access context. */
    suspend fun public(id: UUID): DomainResult<PublicProfile>
    /** Reads a locally cached own profile when available. */
    suspend fun cachedOwn(id: UUID): UserProfile?
    /** Reads a locally cached public profile when available. */
    suspend fun cachedPublic(id: UUID): PublicProfile?
    /** Changes the authenticated user's display name. */
    suspend fun rename(id: UUID, name: String): DomainResult<UserProfile>
    /** Loads the selected public playlist or pin from a profile. */
    suspend fun publicMaterial(owner: UUID, id: UUID, playlist: Boolean): DomainResult<ProfileMaterial>
    /** Returns the current offline state of the profile data source. */
    fun isOffline(): Boolean
}
