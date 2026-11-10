package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface SyncRequester {
    suspend fun requestSync(): DomainResult<SyncRequestStatus>
}
