package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.CreatedShare
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.invalid
import com.soundscape.app.core.domain.model.OpenedShare
import com.soundscape.app.core.domain.model.ShareKind
import com.soundscape.app.core.domain.model.ShareLookup
import com.soundscape.app.core.domain.model.ShareTarget
import com.soundscape.app.core.domain.repository.ShareRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val MIN_SHARE_TOKEN_LENGTH = 32
private const val MAX_SHARE_TOKEN_LENGTH = 256

/** Validates link commands and asks the server to enforce object access. */
class ManageShareUseCase(private val shares: ShareRepository, private val clock: Clock = Clock.systemUTC()) {
    /** Creates an expiring or non-expiring link for a shareable object. */
    suspend fun create(target: ShareTarget, expiresAt: Instant? = null): DomainResult<CreatedShare> {
        return when {
            target.kind == ShareKind.PROFILE -> invalid("kind", "unsupported_link_target")
            expiresAt != null && !expiresAt.isAfter(clock.instant()) -> invalid("expiresAt", "must_be_future")
            else -> shares.create(target, expiresAt)
        }
    }

    /** Revokes a managed link. */
    suspend fun revoke(shareId: UUID): DomainResult<Unit> = shares.revoke(shareId)

    /** Opens only through server resolution, even for a known UUID. */
    suspend fun open(lookup: ShareLookup): DomainResult<OpenedShare> {
        val token = when (lookup) {
            is ShareLookup.Token -> lookup.value
            is ShareLookup.Target -> lookup.token
        }
        if (token != null && !isValidToken(token)) {
            return DomainResult.Failure(DomainError.InvalidShare)
        }
        return shares.open(lookup)
    }

    private fun isValidToken(token: String): Boolean =
        token.length in MIN_SHARE_TOKEN_LENGTH..MAX_SHARE_TOKEN_LENGTH && token.all { it in '!'..'~' }
}
