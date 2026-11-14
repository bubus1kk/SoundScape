package com.soundscape.app

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.soundscape.app.presentation.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** Foreground-only reconnect signal. The data layer still verifies ConnectivityChecker before HTTP. */
@Composable
internal fun ConnectivityRefreshEffect(model: LibraryViewModel) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(true) }
                    override fun onLost(network: Network) { trySend(manager.activeNetwork != null) }
                }
                manager.registerDefaultNetworkCallback(callback)
                trySend(manager.activeNetwork != null)
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.flowOn(Dispatchers.IO).distinctUntilChanged().collect { available ->
                if (available) model.refreshActive()
            }
        }
    }
}
