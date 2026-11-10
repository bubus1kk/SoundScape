package com.soundscape.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Page
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.GeoBounds
import com.soundscape.app.core.domain.model.MusicSearch
import com.soundscape.app.core.domain.usecase.GetVisiblePlacesUseCase
import com.soundscape.app.core.domain.usecase.SearchMusicUseCase
import com.soundscape.app.core.network.NetworkConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NetworkDiagnosticsActivity : ComponentActivity() {
    private val model: NetworkDiagnosticsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme {
                Column {
                    Text(state)
                    Button(onClick = model::loadPlaces) { Text("Load places (permission required)") }
                    Button(onClick = model::searchTracks) { Text("Search tracks (writes/quota; permission required)") }
                }
            }
        }
    }
}

@HiltViewModel
class NetworkDiagnosticsViewModel @Inject constructor(
    private val visible: GetVisiblePlacesUseCase,
    private val search: SearchMusicUseCase,
    private val config: NetworkConfig,
) : ViewModel() {
    private val mutableState = MutableStateFlow("Ready - requests require owner permission")
    val state = mutableState.asStateFlow()
    fun loadPlaces() = launchRequest {
        val sw = (GeoPoint.create(-90.0, -180.0) as DomainResult.Success).value
        val ne = (GeoPoint.create(90.0, 180.0) as DomainResult.Success).value
        visible((GeoBounds.create(sw, ne) as DomainResult.Success).value)
    }
    fun searchTracks() = launchRequest { search(MusicSearch("music")) }
    private fun <T> launchRequest(request: suspend () -> DomainResult<Page<T>>) {
        if (!config.ready) {
            mutableState.value = "Error: configure Supabase URL and publishable key"
            return
        }
        viewModelScope.launch {
            mutableState.value = "Loading"
            mutableState.value = describe(request())
        }
    }
    private fun <T> describe(result: DomainResult<Page<T>>): String = when (result) {
        is DomainResult.Success -> if (result.value.items.isEmpty()) "Empty" else "Content: ${result.value.items}"
        is DomainResult.Failure -> "Error: ${result.error}"
    }
}
