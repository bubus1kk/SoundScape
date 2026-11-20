package com.soundscape.app

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.navigation.SoundScapeNavHost
import com.soundscape.app.presentation.home.HomeAction
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeUiState
import com.soundscape.app.presentation.profile.PersonalProfileActions
import com.soundscape.app.presentation.profile.PersonalProfileScreen
import com.soundscape.app.presentation.profile.ProfileLoad
import com.soundscape.app.presentation.settings.SettingsActions
import com.soundscape.app.presentation.settings.SettingsPage
import com.soundscape.app.presentation.settings.SettingsScreen
import com.soundscape.app.presentation.settings.SettingsUiState
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.presentation.R as PresentationR
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createComposeRule()
    private var dispatch: ((HomeAction) -> Unit)? = null
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun opensSettingsFromProfileAndReturnsThroughExistingBackStack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(text(PresentationR.string.home_profile)).performClick()
            compose.onNodeWithContentDescription(text(PresentationR.string.profile_settings)).performClick()
            compose.onNodeWithText(text(PresentationR.string.settings_app)).assertIsDisplayed()
            compose.onNodeWithText(text(PresentationR.string.nav_music)).assertDoesNotExist()
            compose.onNodeWithText(text(PresentationR.string.settings_appearance)).performClick()
            compose.onNodeWithText(text(PresentationR.string.settings_palette)).assertIsDisplayed()
            pressBack()
            compose.onNodeWithText(text(PresentationR.string.settings_cache)).performClick()
            compose.onNodeWithText(text(PresentationR.string.settings_clear_cache)).assertIsNotEnabled()
            pressBack()
            compose.onNodeWithText(text(PresentationR.string.settings_device)).performClick()
            compose.onNodeWithContentDescription(text(PresentationR.string.settings_notify_places)).assertIsNotEnabled()
            pressBack()
            pressBack()
            compose.onNodeWithText(text(PresentationR.string.profile_mine)).assertIsDisplayed()
        }
    }

    @Test fun appearanceCanChangeOfflineWithLargeTextOnNarrowScreen() {
        val active = mutableStateOf(UserSettings())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.7f)) {
                SoundScapeTheme {
                    Box(Modifier.size(300.dp, 480.dp)) {
                        SettingsScreen(
                            SettingsPage.Appearance, SettingsUiState(settings = active.value),
                            SettingsActions({}, {}, {}, { active.value = active.value.copy(mode = it) }, {}, {}, {}),
                            dynamicColorSupported = false, offline = true,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(text(PresentationR.string.settings_offline)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.settings_dark)).performScrollTo().performClick()
        compose.onNodeWithText(text(PresentationR.string.settings_dark)).assertIsSelected()
    }

    private fun setContent(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SoundScapeTheme {
                SoundScapeNavHost(
                    homeContent = { onAction ->
                        SideEffect { dispatch = onAction }
                        HomeScreen(HomeUiState.Empty(), {}, onAction)
                    },
                    personalContent = { Text(text(PresentationR.string.profile_mine)) },
                )
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

    @Test fun profileActionOpensProfileAndBackRestoresHome() {
        setContent()
        compose.runOnIdle { requireNotNull(dispatch)(HomeAction.Profile) }
        compose.onNodeWithText(text(PresentationR.string.profile_mine)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.nav_library)).assertIsSelected()
        pressBack()
        compose.onNodeWithTag("home-feed").assertIsDisplayed()
    }

    @Test fun guestCanOpenPersonalProfileAndReachRealSignInForm() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val title = text(PresentationR.string.profile_sign_in_title)
            compose.onNodeWithContentDescription(text(PresentationR.string.home_profile)).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(text(PresentationR.string.profile_sign_in)).performClick()
            compose.onNodeWithContentDescription(text(PresentationR.string.profile_email)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(PresentationR.string.profile_password)).assertIsDisplayed()
        }
    }

    @Test fun narrowPersonalProfileWithLargeTextKeepsBothActionsAndLogoutReachable() {
        val profile = UserProfile(UUID.randomUUID(), "u_${"abcd".repeat(10)}")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                SoundScapeTheme {
                    Box(Modifier.size(300.dp, 480.dp)) {
                        PersonalProfileScreen(
                            ProfileLoad.Content(profile),
                            PersonalProfileActions({}, {}, {}, {}, {}, {}, {}, {}),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(profile.displayName).assertIsDisplayed()
        val edit = compose.onNodeWithText(text(PresentationR.string.profile_edit)).fetchSemanticsNode()
        val publicView = compose.onNodeWithText(text(PresentationR.string.profile_public_view)).fetchSemanticsNode()
        assertTrue(publicView.boundsInRoot.top > edit.boundsInRoot.top)
        compose.onNodeWithText(text(PresentationR.string.profile_logout)).performScrollTo().performClick()
        compose.onNodeWithText(text(PresentationR.string.profile_confirm_logout)).assertIsDisplayed()
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
