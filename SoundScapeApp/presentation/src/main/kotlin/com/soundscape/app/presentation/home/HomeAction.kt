package com.soundscape.app.presentation.home

import java.util.UUID

sealed interface HomeAction {
    data object Profile : HomeAction
    data object Search : HomeAction
    data object CreatePlaylist : HomeAction
    data class City(val cityId: UUID, val playlistId: UUID?) : HomeAction
}
