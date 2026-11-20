package com.soundscape.app.navigation

import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.home.HomeAction
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActionNavigationTest {
    private val cityId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val playlistId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun cityWithPlaylistGetsNoticeAboutViewingPlaylists() {
        assertEquals(R.string.home_city_playlist_unavailable, HomeAction.City(cityId, playlistId).noticeResource())
    }

    @Test
    fun cityWithoutPlaylistGetsNoticeAboutMissingPlaylist() {
        assertEquals(R.string.home_city_no_playlist, HomeAction.City(cityId, null).noticeResource())
    }

    @Test
    fun unavailableFeaturesHaveSpecificNotices() {
        assertEquals(R.string.home_profile_unavailable, HomeAction.Profile.noticeResource())
        assertEquals(R.string.home_search_unavailable, HomeAction.Search.noticeResource())
        assertEquals(R.string.home_ai_unavailable, HomeAction.CreatePlaylist.noticeResource())
    }
}
