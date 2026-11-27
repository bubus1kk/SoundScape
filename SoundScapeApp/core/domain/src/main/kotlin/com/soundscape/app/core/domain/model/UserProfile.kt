package com.soundscape.app.core.domain.model

import java.util.UUID
data class UserProfile(val id: UUID, val displayName: String, val bio: String? = null)
