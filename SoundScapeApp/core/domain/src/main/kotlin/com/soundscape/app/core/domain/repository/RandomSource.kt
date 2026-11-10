package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.*

interface RandomSource {
    fun nextIndex(upperBoundExclusive: Int): DomainResult<Int>
}
