package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*
import kotlinx.coroutines.flow.Flow

interface ShakeGestureSource {
    val events: Flow<Unit>
}
