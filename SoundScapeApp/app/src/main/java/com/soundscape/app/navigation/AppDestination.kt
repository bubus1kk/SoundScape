package com.soundscape.app.navigation

import com.soundscape.app.presentation.R

enum class AppDestination(val route: String, val label: Int, val icon: Int) {
    Music("music", R.string.nav_music, R.drawable.ic_music),
    Map("map", R.string.nav_map, R.drawable.ic_map),
    Library("library", R.string.nav_library, R.drawable.ic_library),
}
