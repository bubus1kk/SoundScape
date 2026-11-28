package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.EditorialContent
import com.soundscape.app.core.domain.model.EditorialDraft
import com.soundscape.app.core.domain.model.EditorialReadiness
import com.soundscape.app.core.domain.model.EditorialRevision
import com.soundscape.app.core.domain.model.MediaReference
import com.soundscape.app.core.domain.model.MusicPlace
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.PageRequest
import java.util.UUID

/** Editorial preparation operations requiring server-authorized editorial access. */
interface EditorialRepository {
    /** Lists assigned editorial places. */
    suspend fun cities(page: PageRequest): DomainResult<Page<MusicPlace>>

    /** Loads a city's draft and current lifecycle state. */
    suspend fun draft(placeId: UUID): DomainResult<EditorialDraft>

    /** Saves a draft at an expected revision, with zero denoting the initial save. */
    suspend fun save(expected: EditorialRevision, content: EditorialContent): DomainResult<EditorialDraft>

    /** Requests an authoritative readiness check. */
    suspend fun check(expected: EditorialRevision): DomainResult<EditorialReadiness>

    /** Uploads a cover under the server-authorized editorial upload flow. */
    suspend fun uploadCover(placeId: UUID, image: MediaReference): DomainResult<String>
}

/** Owner administration. The server checks ADMIN and the trusted editorial-owner entitlement. */
interface EditorialPublication {
    /** Records approval of exactly this revision. */
    suspend fun approve(expected: EditorialRevision): DomainResult<EditorialDraft>

    /** Publishes the approved revision separately. */
    suspend fun publish(expected: EditorialRevision): DomainResult<EditorialDraft>

    /** Withdraws the publication using the server revision precondition. */
    suspend fun unpublish(expected: EditorialRevision): DomainResult<EditorialDraft>
}
