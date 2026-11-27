package com.soundscape.app.presentation.map

/** Tracks the native view transitions that need to be balanced when Compose releases it. */
internal class MapLifecycleState {
    var released = false
        private set
    private var started = false
    private var resumed = false

    fun start(onStart: () -> Unit) {
        started = true
        onStart()
    }

    fun resume(onResume: () -> Unit) {
        resumed = true
        onResume()
    }

    fun pause(saveState: () -> Unit, onPause: () -> Unit) {
        saveState()
        resumed = false
        onPause()
    }

    fun stop(onStop: () -> Unit) {
        started = false
        onStop()
    }

    fun release(pause: () -> Unit, stop: () -> Unit): Boolean {
        if (released) return false
        if (resumed) pause()
        if (started) stop()
        released = true
        return true
    }
}
