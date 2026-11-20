package com.soundscape.app

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.navigation.SoundScapeNavHost
import com.soundscape.app.presentation.home.HomeAction
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeUiState
import com.soundscape.app.presentation.R as PresentationR
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createComposeRule()
    private var dispatch: ((HomeAction) -> Unit)? = null
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun setContent(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SoundScapeTheme {
                SoundScapeNavHost(homeContent = { onAction ->
                    SideEffect { dispatch = onAction }
                    HomeScreen(HomeUiState.Empty(), {}, onAction)
                })
            }
        }
        return restoration
    }

    @Test fun tabsRestoreHomeAndBackReturnsToMusicAfterStateRestoration() {
        val restoration = setContent()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Свой плейлист с ИИ"))
        compose.onNodeWithText("Карта").performClick()
        compose.onNodeWithText(text(PresentationR.string.home_pending)).assertExists()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Свой плейлист с ИИ").assertIsDisplayed()
        compose.onNodeWithText("Моя медиатека").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(PresentationR.string.home_pending)).assertExists()
        pressBack()
        compose.onNodeWithTag("home-feed").assertExists()
        compose.onNodeWithText("Карта").performClick()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithTag("home-feed").assertExists()
    }

    @Test fun repeatedActionsShowOneNoticeAndKeepHomeVisible() {
        setContent()
        for ((action, message) in listOf(
            HomeAction.Profile to PresentationR.string.home_profile_unavailable,
            HomeAction.Search to PresentationR.string.home_search_unavailable,
            HomeAction.CreatePlaylist to PresentationR.string.home_ai_unavailable,
        )) {
            compose.runOnIdle { repeat(5) { requireNotNull(dispatch)(action) } }
            compose.onNodeWithText(text(message)).assertIsDisplayed()
            compose.onNodeWithText("Музыка").assertIsSelected()
            compose.onNodeWithTag("home-feed").assertExists()
        }
        compose.onNodeWithText("Карта").performClick()
        pressBack()
        compose.onNodeWithTag("home-feed").assertExists()
    }

    @Test fun cityWithoutPlaylistHasHonestNoticeAndDoesNotOpenPlaylistScreen() {
        setContent()
        val city = java.util.UUID.fromString("11111111-1111-4111-8111-111111111111")
        compose.runOnIdle { repeat(5) { requireNotNull(dispatch)(HomeAction.City(city, null)) } }
        compose.onNodeWithText(text(PresentationR.string.home_city_no_playlist)).assertIsDisplayed()
        compose.onNodeWithText("Музыка").assertIsSelected()
        compose.onNodeWithTag("home-feed").assertExists()
        val playlist = java.util.UUID.fromString("22222222-2222-4222-8222-222222222222")
        compose.runOnIdle { requireNotNull(dispatch)(HomeAction.City(city, playlist)) }
        compose.onNodeWithText(text(PresentationR.string.home_city_playlist_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.home_city_no_playlist)).assertDoesNotExist()
    }
}
