package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.EditorialDraft
import com.soundscape.app.core.domain.model.EditorialRevision
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.repository.EditorialPublication

/** Coordinates separate editorial owner actions against explicit revisions. */
class PublishEditorialUseCase(private val publication: EditorialPublication) {
    /** Asks the server to approve the exact revision the owner reviewed. */
    suspend fun approve(expected: EditorialRevision): DomainResult<EditorialDraft> =
        if (expected.revision < 1) invalid("revision", "positive") else publication.approve(expected)

    /** Publishes only the revision with matching recorded approval. */
    suspend fun publish(draft: EditorialDraft): DomainResult<EditorialDraft> {
        if (draft.approval?.revision != draft.version.revision) return invalid("approval", "revision_not_approved")
        return publication.publish(draft.version)
    }

    /** Withdraws the publication. The server still checks authority and concurrency. */
    suspend fun withdraw(expected: EditorialRevision): DomainResult<EditorialDraft> =
        if (expected.revision < 1) invalid("revision", "positive") else publication.unpublish(expected)
}
