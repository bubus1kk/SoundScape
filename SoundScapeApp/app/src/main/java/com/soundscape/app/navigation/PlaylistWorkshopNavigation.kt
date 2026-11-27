package com.soundscape.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.soundscape.app.presentation.generation.PlaylistGeneratorScreen
import com.soundscape.app.presentation.generation.PlaylistResultScreen
import com.soundscape.app.presentation.generation.PlaylistWorkshopViewModel
import kotlinx.coroutines.flow.Flow

internal const val GENERATOR_ROUTE = "playlist/generator"
internal const val GENERATION_RESULT_ROUTE = "playlist/generator/result"

internal fun NavGraphBuilder.installWorkshopRoutes(
    controller: NavHostController,
    offline: Flow<Boolean>?,
    modelFor: @Composable (NavBackStackEntry) -> PlaylistWorkshopViewModel = { hiltViewModel(it) },
    signIn: () -> Unit = { controller.openProfileRoute(ProfileNavigation.SignIn) },
    generatorChrome: (Boolean) -> Unit = {},
) {
    composable(GENERATOR_ROUTE) { entry ->
        val model = modelFor(entry)
        val state by model.state.collectAsStateWithLifecycle()
        val disconnected = offline?.collectAsStateWithLifecycle(initialValue = false)?.value ?: false
        DisposableEffect(Unit) { onDispose { generatorChrome(true) } }
        PlaylistGeneratorScreen(state, model, disconnected, { controller.popBackStack() }, signIn,
            openResult = {
                if (controller.currentBackStackEntry == entry) {
                    controller.navigate(GENERATION_RESULT_ROUTE) { launchSingleTop = true }
                }
            }, chromeVisible = generatorChrome)

    }
    composable(GENERATION_RESULT_ROUTE) { entry ->
        val parent = remember(entry) { controller.getBackStackEntry(GENERATOR_ROUTE) }
        val model = modelFor(parent)
        val state by model.state.collectAsStateWithLifecycle()
        val disconnected = offline?.collectAsStateWithLifecycle(initialValue = false)?.value ?: false
        PlaylistResultScreen(state, model, disconnected, { controller.popBackStack() }, signIn)
    }
}
