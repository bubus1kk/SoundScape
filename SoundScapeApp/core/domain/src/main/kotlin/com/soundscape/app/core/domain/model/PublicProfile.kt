package com.soundscape.app.core.domain.model

import java.util.UUID

data class PublicProfile(
    val user: UserProfile,
    val playlists: List<ProfileMaterial>,
    val pins: List<ProfileMaterial>,
)

data class ProfileMaterial(
    val id: UUID,
    val title: String,
    val trackCount: Int,
    val description: String? = null,
)

fun profileNameError(name: String): DomainError.Validation? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> DomainError.Validation("name", "required")
        trimmed.codePointCount(0, trimmed.length) > 40 -> DomainError.Validation("name", "max_40")
        else -> null
    }
}
