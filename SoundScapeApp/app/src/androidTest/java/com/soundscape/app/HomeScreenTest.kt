package com.soundscape.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeoPoint
import com.soundscape.app.core.domain.model.MapPlace
import com.soundscape.app.core.domain.model.MapPlaceKind
import com.soundscape.app.presentation.home.HomeScreen
import com.soundscape.app.presentation.home.HomeAction
import com.soundscape.app.presentation.home.HomeUiState
import com.soundscape.app.presentation.R as PresentationR
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun successfulEmptyDoesNotRenderLoadError() {
        compose.setContent { SoundScapeTheme { HomeScreen(HomeUiState.Empty(), {}, {}) } }
        compose.onNodeWithText(text(PresentationR.string.home_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.home_error)).assertDoesNotExist()
        compose.onNodeWithText(text(PresentationR.string.home_refresh_error)).assertDoesNotExist()
        compose.onNodeWithText(text(PresentationR.string.settings_retry)).assertExists()
    }

    @Test fun statesCachedEmptyAndRetryAreRendered() {
        val state = mutableStateOf<HomeUiState>(HomeUiState.Loading)
        var retries = 0
        compose.setContent { SoundScapeTheme { HomeScreen(state.value, { retries++ }, {}) } }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Музыка со всего мира"))
        compose.onNodeWithTag("home-loading").assertExists()
        compose.runOnIdle { state.value = HomeUiState.Error(DomainError.Network) }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Повторить"))
        compose.onNodeWithText("Повторить").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnIdle { state.value = HomeUiState.Empty(fromCache = true, refreshError = DomainError.Network) }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Опубликованных городов пока нет"))
        compose.onNodeWithText("Опубликованных городов пока нет").assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.home_cached_empty_description)).assertExists()
        compose.onNodeWithText(text(PresentationR.string.home_offline)).assertExists()
        val city = MapPlace(UUID.randomUUID(), MapPlaceKind.CITY, "Тестовый город",
            (GeoPoint.create(0.0, 0.0) as DomainResult.Success).value)
        compose.runOnIdle { state.value = HomeUiState.Content(listOf(city), fromCache = true,
            refreshError = DomainError.Network) }
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Тестовый город"))
        compose.onNodeWithText("Тестовый город").assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.home_cache_offline)).assertExists()
    }

    @Test fun actionsAreTypedAndCityIdsSurviveWithOrWithoutPlaylist() {
        val cityId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val playlistId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val city = MapPlace(cityId, MapPlaceKind.CITY, "Тестовый город",
            (GeoPoint.create(0.0, 0.0) as DomainResult.Success).value, playlistId = playlistId)
        val state = mutableStateOf<HomeUiState>(HomeUiState.Content(listOf(city)))
        val actions = mutableListOf<HomeAction>()
        compose.setContent { SoundScapeTheme { HomeScreen(state.value, {}, actions::add) } }
        compose.onNodeWithText(city.title).performClick()
        compose.runOnIdle { state.value = HomeUiState.Content(listOf(city.copy(playlistId = null))) }
        compose.onNodeWithText(city.title).performClick()
        compose.onNodeWithContentDescription(text(PresentationR.string.home_profile)).performClick()
        compose.onNodeWithText(text(PresentationR.string.home_search)).performClick()
        compose.onNodeWithTag("home-feed").performScrollToNode(hasText(text(PresentationR.string.home_create)))
        compose.onNodeWithText(text(PresentationR.string.home_create)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(
                HomeAction.City(cityId, playlistId), HomeAction.City(cityId, null),
                HomeAction.Profile, HomeAction.Search, HomeAction.CreatePlaylist,
            ), actions)
        }
    }

    @Test fun cachedEmptyDuringRefreshAndTimeoutRemainsEmpty() {
        val state = mutableStateOf<HomeUiState>(HomeUiState.Empty(fromCache = true, refreshing = true))
        compose.setContent { SoundScapeTheme { HomeScreen(state.value, {}, {}) } }
        compose.onNodeWithText(text(PresentationR.string.home_cached_empty_description)).assertExists()
        compose.onNodeWithText(text(com.soundscape.app.core.designsystem.R.string.soundscape_loading))
            .assertIsNotEnabled()
        compose.runOnIdle {
            state.value = HomeUiState.Empty(fromCache = true, refreshError = DomainError.Timeout)
        }
        compose.onNodeWithText(text(PresentationR.string.home_timeout)).assertExists()
        compose.onNodeWithText(text(PresentationR.string.home_offline)).assertDoesNotExist()
        compose.onNodeWithText(text(PresentationR.string.home_cached_empty_description)).assertExists()
    }

    @Test fun timeoutAndServerErrorDoNotClaimConnectionLoss() {
        val state = mutableStateOf<HomeUiState>(HomeUiState.Error(DomainError.Timeout))
        compose.setContent { SoundScapeTheme { HomeScreen(state.value, {}, {}) } }
        compose.onNodeWithText(text(PresentationR.string.home_timeout)).assertExists()
        compose.onNodeWithText(text(PresentationR.string.home_offline)).assertDoesNotExist()
        compose.runOnIdle { state.value = HomeUiState.Error(DomainError.Server) }
        compose.onNodeWithText(text(PresentationR.string.home_refresh_error)).assertExists()
        compose.onNodeWithText(text(PresentationR.string.home_timeout)).assertDoesNotExist()
    }
}
