package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.SyncRequestStatus

/** Requests synchronization through the background scheduling adapter. */
interface SyncRequester {
    /** Schedules synchronization or reports an existing pending request. */
    suspend fun requestSync(): DomainResult<SyncRequestStatus>
}
