package com.soundscape.app.core.domain.model

import java.util.UUID

private const val MAX_PROFILE_NAME_LENGTH = 40

/** Public profile and the material visible to its viewer. */
data class PublicProfile(
    val user: UserProfile,
    val playlists: List<ProfileMaterial>,
    val pins: List<ProfileMaterial>,
)

/** Summary of a playlist or pin on a public profile. */
data class ProfileMaterial(
    val id: UUID,
    val title: String,
    val trackCount: Int,
    val description: String? = null,
)

/** Checks the trimmed name using its Unicode code point count. */
fun profileNameError(name: String): DomainError.Validation? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> DomainError.Validation("name", "required")
        trimmed.codePointCount(0, trimmed.length) > MAX_PROFILE_NAME_LENGTH -> DomainError.Validation("name", "max_40")
        else -> null
    }
}
