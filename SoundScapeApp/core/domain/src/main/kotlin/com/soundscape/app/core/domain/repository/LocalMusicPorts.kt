package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.AudioCacheLimit
import com.soundscape.app.core.domain.model.AuthorizedDownload
import com.soundscape.app.core.domain.model.CachedAudio
import com.soundscape.app.core.domain.model.CachedTrackMetadata
import com.soundscape.app.core.domain.model.ConflictChoice
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.LocalAccountScope
import com.soundscape.app.core.domain.model.ListeningEvent
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.model.SyncClaim
import com.soundscape.app.core.domain.model.SyncOperation
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Local account outbox. Enqueue, claim, completion and conflict decisions must be atomic. */
interface SyncOutbox {
    /** Observes persisted state for this account. */
    fun observe(accountId: UUID): Flow<List<SyncOperation>>

    /** Persists a new immutable request or returns the matching existing operation. */
    suspend fun enqueue(operation: SyncOperation): DomainResult<SyncOperation>

    /** Claims the next eligible operation, recovering abandoned claims after a restart. */
    suspend fun claim(accountId: UUID): DomainResult<SyncClaim?>

    /** Updates the claimed operation or removes it only after successful application. */
    suspend fun finish(claimed: SyncClaim, result: DomainResult<Unit>): DomainResult<Unit>

    /** Applies an explicit choice atomically, keeping local data until the decision is persisted. */
    suspend fun resolve(conflicted: SyncOperation, choice: ConflictChoice): DomainResult<Unit>
}

/** Metadata can be cached independently of audio rights. */
interface TrackMetadataCache {
    /** Reads locally stored metadata. */
    suspend fun read(trackId: UUID): CachedTrackMetadata?
}

/** Audio cache stores only audio authorized for caching. */
interface AudioCache {
    /** Returns cache state for a recording. */
    suspend fun read(trackId: UUID): CachedAudio?

    /** Applies the user's supported storage budget. */
    suspend fun setLimit(limit: AudioCacheLimit): DomainResult<Unit>

    /** Removes cached audio. */
    suspend fun clear(): DomainResult<Unit>
}

/** User-initiated download of audio with an explicit grant. */
fun interface AudioDownloads {
    /** Saves audio to the user's downloads. */
    suspend fun download(request: AuthorizedDownload): DomainResult<Unit>
}

/** Device-local history scoped to a guest or account. */
interface ListeningHistory {
    /** Observes local history settings and entries within the supplied scope. */
    fun enabled(scope: LocalAccountScope): Flow<Boolean>

    /** Reads the newest local entries first. */
    suspend fun list(scope: LocalAccountScope, page: PageRequest): DomainResult<Page<ListeningEvent>>

    /** Saves an event only when history is enabled for the scope. */
    suspend fun record(scope: LocalAccountScope, event: ListeningEvent): DomainResult<Unit>

    /** Enables or disables recording for the scope. */
    suspend fun setEnabled(scope: LocalAccountScope, enabled: Boolean): DomainResult<Unit>

    /** Deletes a single local history entry. */
    suspend fun delete(scope: LocalAccountScope, id: UUID): DomainResult<Unit>

    /** Clears the selected scope. */
    suspend fun clear(scope: LocalAccountScope): DomainResult<Unit>
}
