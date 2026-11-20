package com.soundscape.app.presentation.home

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.MapPlace

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(
        val cities: List<MapPlace>,
        val refreshing: Boolean = false,
        val fromCache: Boolean = false,
        val refreshError: DomainError? = null,
    ) : HomeUiState
    data class Empty(
        val refreshing: Boolean = false,
        val fromCache: Boolean = false,
        val refreshError: DomainError? = null,
    ) : HomeUiState
    data class Error(val error: DomainError) : HomeUiState
}
