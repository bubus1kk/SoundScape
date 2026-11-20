package com.soundscape.app.navigation

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.soundscape.app.presentation.settings.SettingsActions
import com.soundscape.app.presentation.settings.SettingsPage
import com.soundscape.app.presentation.settings.SettingsScreen
import com.soundscape.app.presentation.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow

internal const val SETTINGS_ROUTE_PREFIX = "settings/"

internal val SettingsPage.route: String get() = SETTINGS_ROUTE_PREFIX + name

internal fun NavGraphBuilder.installSettingsRoutes(controller: NavHostController, offline: Flow<Boolean>?) {
    SettingsPage.entries.forEach { page ->
        composable(page.route) {
            val model: SettingsViewModel = hiltViewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var downloadsFailed by rememberSaveable { mutableStateOf(false) }

            SettingsScreen(
                page,
                state,
                SettingsActions(
                    back = { controller.popBackStack() },
                    open = { controller.navigate(it.route) { launchSingleTop = true } },
                    retry = model::retryRead,
                    mode = model::setMode,
                    preset = model::setPreset,
                    dynamic = model::setDynamicColor,
                    downloads = {
                        downloadsFailed = try {
                            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                            false
                        } catch (_: ActivityNotFoundException) {
                            true
                        } catch (_: SecurityException) {
                            true
                        }
                    },
                ),
                dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                downloadsFailed = downloadsFailed,
                offline = offline?.collectAsStateWithLifecycle(initialValue = false)?.value == true,
            )
        }
    }
}
