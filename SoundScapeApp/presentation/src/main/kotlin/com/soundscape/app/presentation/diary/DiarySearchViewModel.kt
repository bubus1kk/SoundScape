package com.soundscape.app.presentation.diary

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.repository.TrackSearcher

@dagger.hilt.android.lifecycle.HiltViewModel
class DiarySearchViewModel @javax.inject.Inject constructor(
    private val searcher: TrackSearcher,
) : androidx.lifecycle.ViewModel() {
    private val mutable =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.soundscape.app.core.domain.model.Track>>(emptyList())
    val tracks = mutable.asStateFlow()
    private val mutableError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var job: kotlinx.coroutines.Job? = null
    fun search(query: String) {
        job?.cancel()
        job = viewModelScope.launch {
            when (val result =
                com.soundscape.app.core.domain.usecase.SearchMusicUseCase(searcher)(MusicSearch(query))) {
                is DomainResult.Success -> { mutable.value = result.value.items; mutableError.value = null }
                is DomainResult.Failure -> mutableError.value =
                    "Поиск недоступен. Можно сохранить черновик без новых треков."
            }
        }
    }
}
