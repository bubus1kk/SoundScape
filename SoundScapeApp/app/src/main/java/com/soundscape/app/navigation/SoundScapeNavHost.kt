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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.soundscape.app.core.domain.model.MediaImageRequest
import com.soundscape.app.core.domain.model.MediaObjectType
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.presentation.search.GlobalSearchScreen
import com.soundscape.app.presentation.search.GlobalSearchViewModel
import com.soundscape.app.BuildConfig
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.media.CityPhoto
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeAction
import com.soundscape.app.presentation.home.HomeViewModel
import com.soundscape.app.presentation.map.MapScreen
import com.soundscape.app.presentation.map.MapViewModel
import kotlinx.coroutines.flow.Flow

private const val SEARCH_ROUTE = "search/{origin}"

@Composable
fun SoundScapeNavHost(imageLoader: ImageLoader, offline: Flow<Boolean>? = null) {
    SoundScapeNavHost(
        homeContent = { HomeRoute(it, imageLoader) },
        imageLoader = imageLoader,
        offline = offline,
        mapTilerKey = BuildConfig.MAPTILER_API_KEY,
    )
}

@Composable
internal fun SoundScapeNavHost(
    homeContent: @Composable ((HomeAction) -> Unit) -> Unit,
    imageLoader: ImageLoader? = null,
    personalContent: @Composable ((() -> Unit) -> Unit)? = null,
    offline: Flow<Boolean>? = null,
    mapTilerKey: String = "",
) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    var generatorChrome by remember { mutableStateOf(true) }
    Scaffold(
        containerColor = SoundScapeTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val route = entry?.destination?.route
            if (showsBottomBar(route, generatorChrome)) {
                val selected = when (route) {
                    SEARCH_ROUTE -> entry?.arguments?.getString("origin")
                    GENERATOR_ROUTE, GENERATION_RESULT_ROUTE -> AppDestination.Music.route
                    else -> if (route?.startsWith("profile") == true) AppDestination.Library.route else route
                }
                AppBottomBar(selected, personalProfile = route == ProfileNavigation.Own) { destination ->
                    controller.goToTab(destination.route)
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = AppDestination.Music.route, modifier = Modifier.padding(padding)) {
            composable(AppDestination.Music.route) {
                HomeWithNotices(snackbar, homeContent,
                    openProfile = { controller.openProfileRoute(ProfileNavigation.Own) },
                    openSearch = { controller.navigate("search/music") { launchSingleTop = true } },
                    openGenerator = { controller.navigate(GENERATOR_ROUTE) { launchSingleTop = true } },
                )
            }
            composable(AppDestination.Map.route) {
                val model = if (mapTilerKey.isBlank()) null else hiltViewModel<MapViewModel>()
                val isOffline = offline?.collectAsStateWithLifecycle(initialValue = false)?.value ?: false
                MapScreen(mapTilerKey, model, isOffline,
                    onSearch = { controller.navigate("search/map") { launchSingleTop = true } },
                    onProfile = { controller.openProfileRoute(ProfileNavigation.Own) },
                )
            }
            composable(AppDestination.Library.route) { PendingScreen(stringResource(R.string.nav_library)) {
                controller.popBackStack(AppDestination.Music.route, false)
            } }
            composable(SEARCH_ROUTE, arguments = listOf(navArgument("origin") { type = NavType.StringType })) {
                SearchRoute(controller, imageLoader, offline)
            }
            installWorkshopRoutes(controller, offline, generatorChrome = { generatorChrome = it })
            installProfileRoutes(controller, imageLoader, personalContent)
            installSettingsRoutes(controller, offline)
        }
    }
}

private fun showsBottomBar(route: String?, generatorChrome: Boolean): Boolean = when (route) {
    ProfileNavigation.Edit, ProfileNavigation.SignIn -> false
    GENERATOR_ROUTE -> generatorChrome
    else -> route?.startsWith(SETTINGS_ROUTE_PREFIX) != true
}

private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun SearchRoute(controller: NavHostController, imageLoader: ImageLoader?, offline: Flow<Boolean>?) {
    val model: GlobalSearchViewModel = hiltViewModel()
    val playback: SearchPlaybackViewModel = hiltViewModel()
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    val state by model.state.collectAsStateWithLifecycle()
    val isOffline = offline?.collectAsStateWithLifecycle(initialValue = false)?.value ?: false
    LaunchedEffect(state.query.trim(), state.category, state.filters) { playback.pause() }
    GlobalSearchScreen(state, model, isOffline, { controller.popBackStack() },
        { controller.openProfileRoute(ProfileNavigation.public(it.id)) },
        playbackState, { hit -> playback.toggle(hit) { uriHandler.openUri(it.toString()) } },
    ) { hit, modifier ->
        val type = when (hit.category) {
            SearchCategory.PLACE -> MediaObjectType.PLACE
            SearchCategory.PLAYLIST -> MediaObjectType.PLAYLIST
            else -> null
        }
        if (type != null && imageLoader != null) {
            AsyncImage(MediaImageRequest(type, hit.id), null, imageLoader, modifier,
                contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun PendingScreen(title: String, back: () -> Unit) {
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
    openProfile: () -> Unit,
    openSearch: () -> Unit,
    openGenerator: () -> Unit,
) {
    var notice by remember { mutableStateOf<Int?>(null) }
    homeContent { action ->
        when (action) {
            HomeAction.Profile -> openProfile()
            HomeAction.Search -> openSearch()
            HomeAction.CreatePlaylist -> openGenerator()
            else -> notice = action.noticeResource()
        }
    }
    val message = notice?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            notice = null
        }
    }
}

internal fun HomeAction.noticeResource(): Int? = when (this) {
    HomeAction.Profile -> null
    HomeAction.Search -> R.string.home_search_unavailable
    HomeAction.CreatePlaylist -> R.string.home_ai_unavailable
    is HomeAction.City -> if (playlistId == null) R.string.home_city_no_playlist
        else R.string.home_city_playlist_unavailable
}
