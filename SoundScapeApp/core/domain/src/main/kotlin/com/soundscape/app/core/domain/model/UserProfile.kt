package com.soundscape.app.core.domain.model

import java.util.UUID
/** Profile fields safe for existing current and public profile consumers. */
data class UserProfile(val id: UUID, val displayName: String, val bio: String? = null, val hasAvatar: Boolean = false)
