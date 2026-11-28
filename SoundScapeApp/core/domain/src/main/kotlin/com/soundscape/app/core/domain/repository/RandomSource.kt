package com.soundscape.app.core.domain.repository

import com.soundscape.app.core.domain.model.DomainResult

/** Supplies random selection indices for discovery. */
interface RandomSource {
    /** Selects a zero-based index strictly below the supplied bound. */
    fun nextIndex(upperBoundExclusive: Int): DomainResult<Int>
}
