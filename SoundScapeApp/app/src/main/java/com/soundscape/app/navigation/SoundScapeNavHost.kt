package com.soundscape.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.media.CityPhoto
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeAction
import com.soundscape.app.presentation.home.HomeViewModel

@Composable
fun SoundScapeNavHost(imageLoader: ImageLoader) {
    SoundScapeNavHost(homeContent = { HomeRoute(it, imageLoader) })
}

@Composable
internal fun SoundScapeNavHost(homeContent: @Composable ((HomeAction) -> Unit) -> Unit) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        containerColor = SoundScapeTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AppBottomBar(entry?.destination?.route) { destination ->
                controller.navigate(destination.route) {
                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = AppDestination.Music.route, modifier = Modifier.padding(padding)) {
            composable(AppDestination.Music.route) {
                HomeWithNotices(snackbar, homeContent)
            }
            composable(AppDestination.Map.route) { PendingScreen(stringResource(R.string.nav_map)) {
                controller.popBackStack(AppDestination.Music.route, false)
            } }
            composable(AppDestination.Library.route) { PendingScreen(stringResource(R.string.nav_library)) {
                controller.popBackStack(AppDestination.Music.route, false)
            } }
        }
    }
}

@Composable
private fun PendingScreen(title: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(SoundScapeTheme.spacing.space24)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.home_pending),
            modifier = Modifier.padding(vertical = SoundScapeTheme.spacing.space16))
        SoundScapeButton(stringResource(R.string.home_back), back)
    }
}

@Composable
private fun HomeRoute(onAction: (HomeAction) -> Unit, imageLoader: ImageLoader) {
    val model: HomeViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    HomeScreen(state, model::retry, onAction) { cityId, modifier ->
        CityPhoto(cityId, imageLoader, modifier)
    }
}

@Composable
private fun HomeWithNotices(
    snackbar: SnackbarHostState,
    homeContent: @Composable ((HomeAction) -> Unit) -> Unit,
) {
    var notice by remember { mutableStateOf<Int?>(null) }
    homeContent { notice = it.noticeResource() }
    val message = notice?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            notice = null
        }
    }
}

internal fun HomeAction.noticeResource(): Int = when (this) {
    HomeAction.Profile -> R.string.home_profile_unavailable
    HomeAction.Search -> R.string.home_search_unavailable
    HomeAction.CreatePlaylist -> R.string.home_ai_unavailable
    is HomeAction.City -> if (playlistId == null) R.string.home_city_no_playlist
        else R.string.home_city_playlist_unavailable
}
