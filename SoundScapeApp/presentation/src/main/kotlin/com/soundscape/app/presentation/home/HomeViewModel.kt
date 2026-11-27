package com.soundscape.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.CachedPage
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.core.domain.model.PageRequest
import com.soundscape.app.core.domain.usecase.ObserveVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.RefreshVisiblePlacesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observe: ObserveVisiblePlacesUseCase,
    private val refresh: RefreshVisiblePlacesUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state = mutableState.asStateFlow()
    private var loading: Job? = null

    init { retry() }

    fun retry() {
        if (loading?.isActive == true) return
        loading = viewModelScope.launch {
            try {
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(DomainError.Unexpected)
            } finally {
                val content = mutableState.value as? HomeUiState.Content
                if (content != null) mutableState.value = content.copy(refreshing = false)
                val empty = mutableState.value as? HomeUiState.Empty
                if (empty != null) mutableState.value = empty.copy(refreshing = false)
            }
        }
    }

    private suspend fun load() {
        val previous = mutableState.value as? HomeUiState.Content
        val empty = mutableState.value as? HomeUiState.Empty
        mutableState.value = previous?.copy(refreshing = true, refreshError = null)
            ?: empty?.copy(refreshing = true, refreshError = null) ?: HomeUiState.Loading
        if (previous == null && empty == null) restoreCache()
        when (val result = refresh(WORLD, PAGE)) {
            is DomainResult.Failure -> fail(result.error)
            is DomainResult.Success -> {
                currentCoroutineContext().ensureActive()
                val snapshot = observe(WORLD, PAGE).first().cached
                if (snapshot == null) fail(DomainError.Unexpected)
                else {
                    val cities = cities(snapshot)
                    mutableState.value = if (cities.isEmpty()) HomeUiState.Empty() else HomeUiState.Content(cities)
                }
            }
        }
    }

    private suspend fun restoreCache() {
        val snapshot = observe(WORLD, PAGE).first().cached ?: return
        val cities = cities(snapshot)
        mutableState.value = if (cities.isEmpty()) HomeUiState.Empty(refreshing = true, fromCache = true)
        else HomeUiState.Content(cities, refreshing = true, fromCache = true)
    }

    private fun cities(snapshot: CachedPage<MapPlace>) = snapshot.page.items
        .filter { it.kind == MapPlaceKind.CITY }.distinctBy { it.id }.take(CITY_LIMIT)

    private fun fail(error: DomainError) {
        val content = mutableState.value as? HomeUiState.Content
        val empty = mutableState.value as? HomeUiState.Empty
        mutableState.value = content?.copy(refreshing = false, refreshError = error)
            ?: empty?.copy(refreshing = false, refreshError = error) ?: HomeUiState.Error(error)
    }

    companion object {
        private const val CITY_LIMIT = 20

        private val PAGE = PageRequest(limit = 100, offset = 0)
        private val WORLD = GeoBounds.create(
            (GeoPoint.create(-90.0, -180.0) as DomainResult.Success).value,
            (GeoPoint.create(90.0, 180.0) as DomainResult.Success).value,
        ).let { (it as DomainResult.Success).value }
    }
}
