package com.soundscape.app.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** Emits platform-detected shake events for discovery. */
interface ShakeGestureSource {
    val events: Flow<Unit>
}
