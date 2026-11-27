package com.soundscape.app

import android.view.View
import android.view.ViewGroup
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
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
import org.junit.Assert.assertNotSame
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Point
import kotlin.math.abs

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
        compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.map_config_missing)).assertIsDisplayed()
        compose.onNodeWithText("Карта").assertIsSelected()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Свой плейлист с ИИ").assertIsDisplayed()
        compose.onNodeWithText("Моя медиатека").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(PresentationR.string.home_pending)).assertExists()
        pressBack()
        compose.onNodeWithTag("home-feed").assertExists()
        compose.onNodeWithText("Карта").performClick()
        compose.onNodeWithText(text(PresentationR.string.map_config_missing)).assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithTag("home-feed").assertExists()
    }

    @Test fun repeatedUnavailableCityActionsShowOneNoticeAndKeepHomeVisible() {
        setContent()
        val city = UUID.randomUUID()
        val playlist = UUID.randomUUID()
        for ((action, message) in listOf(
            HomeAction.City(city, null) to PresentationR.string.home_city_no_playlist,
            HomeAction.City(city, playlist) to PresentationR.string.home_city_playlist_unavailable,
        )) {
            compose.runOnIdle { repeat(5) { requireNotNull(dispatch)(action) } }
            compose.onNodeWithText(text(message)).assertIsDisplayed()
            compose.onAllNodesWithText(text(message)).assertCountEquals(1)
            compose.onNodeWithText("Музыка").assertIsSelected()
            compose.onNodeWithTag("home-feed").assertExists()
        }
        compose.onNodeWithText("Карта").performClick()
        pressBack()
        compose.onNodeWithTag("home-feed").assertExists()
    }

    @Test fun opensSearchFromHomeAndReturnsThroughExistingBackStack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(text(PresentationR.string.home_search)).performClick()
            compose.onNodeWithText(text(PresentationR.string.search_title)).assertIsDisplayed()
            compose.onNodeWithText(text(PresentationR.string.search_tracks)).performClick()
            pressBack()
            compose.onNodeWithTag("home-feed").assertExists()
        }
    }

    @Test fun mapViewIsRecreatedAndReleasedWhenLeavingTabOrRecreatingActivity() {
        assumeTrue(BuildConfig.MAPTILER_API_KEY.isNotBlank())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(text(PresentationR.string.nav_map)).performClick()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
            val original = activeMap(scenario)
            compose.onNodeWithText(text(PresentationR.string.nav_music)).performClick()
            compose.onNodeWithText(text(PresentationR.string.nav_music)).assertIsSelected()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.waitUntil(timeoutMillis = 30_000) { compose.runOnUiThread { original.isDestroyed } }
            compose.onNodeWithText(text(PresentationR.string.nav_map)).performClick()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
            val restored = activeMap(scenario)
            assertNotSame(original, restored)
            scenario.recreate()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
            assertNotSame(restored, activeMap(scenario))
            assertTrue(compose.runOnUiThread { restored.isDestroyed })
        }
    }

    @Test fun realClusterZoomsAndPublishedCityOpensItsCard() {
        assumeTrue(BuildConfig.MAPTILER_API_KEY.isNotBlank())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(text(PresentationR.string.nav_map)).performClick()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
            val view = activeMap(scenario)
            var ready: MapLibreMap? = null
            compose.runOnUiThread { view.getMapAsync { ready = it } }
            compose.waitUntil(timeoutMillis = 30_000) { compose.runOnUiThread { ready?.style?.isFullyLoaded == true } }
            val map = requireNotNull(ready)
            val paris = LatLng(48.8566, 2.3522)
            compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.newLatLngZoom(paris, 2.0)) }
            val center = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            fun clusterPoint() = map.queryRenderedFeatures(center, "soundscape-clusters")
                .firstOrNull()?.geometry() as? Point
            compose.waitUntil(timeoutMillis = 45_000) { compose.runOnUiThread { clusterPoint() != null } }
            compose.runOnUiThread {
                val cluster = requireNotNull(clusterPoint())
                tapMap(view, map.projection.toScreenLocation(LatLng(cluster.latitude(), cluster.longitude())))
            }
            compose.waitUntil(timeoutMillis = 10_000) { compose.runOnUiThread { map.cameraPosition.zoom > 2.0 } }
            compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.newLatLngZoom(paris, 5.0)) }
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.runOnUiThread {
                    map.queryRenderedFeatures(map.projection.toScreenLocation(paris), "soundscape-places").isNotEmpty()
                }
            }
            compose.runOnUiThread { tapMap(view, map.projection.toScreenLocation(paris)) }
            val cityKind = text(PresentationR.string.map_card_city)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(cityKind)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Париж").assertIsDisplayed()
            pressBack()
            compose.onNodeWithText(cityKind).assertDoesNotExist()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
        }
    }

    @Test fun ss09RandomPlaceFliesFromWorldViewAndOpensFocusedMarkerCard() = withOpenMap { _, map ->
        compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.zoomTo(map.minZoomLevel)) }
        val start = compose.runOnUiThread { map.cameraPosition.zoom }
        val button = compose.onNodeWithContentDescription(text(PresentationR.string.map_random))
        compose.waitUntil(30_000) { runCatching { button.assertIsEnabled() }.isSuccess }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(PresentationR.string.map_zoom_in))).fetchSemanticsNodes().isNotEmpty()
        }
        button.performClick()
        val card = hasText(text(PresentationR.string.map_card_city)) or hasText(text(PresentationR.string.map_card_pin))
        compose.waitUntil(30_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
        val opened = compose.runOnUiThread {
            val finish = map.cameraPosition
            val center = requireNotNull(finish.target)
            val pin = map.queryRenderedFeatures(map.projection.toScreenLocation(center), "soundscape-focused-place-pin")
            assertTrue(pin.isNotEmpty())
            assertTrue(finish.zoom > start)
            requireNotNull(pin.first().id())
        }
        val firstCenter = compose.runOnUiThread { map.cameraPosition.target }
        val firstZoom = compose.runOnUiThread { map.cameraPosition.zoom }
        var overviewZoom = firstZoom
        val observeZoom = MapLibreMap.OnCameraMoveListener {
            overviewZoom = minOf(overviewZoom, map.cameraPosition.zoom)
        }
        pressBack()
        compose.onNode(card).assertDoesNotExist()
        button.assertIsEnabled()
        compose.runOnUiThread { map.addOnCameraMoveListener(observeZoom) }
        try {
            button.performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread {
                val finish = map.cameraPosition
                val center = requireNotNull(finish.target)
                val pin = map.queryRenderedFeatures(map.projection.toScreenLocation(center), "soundscape-focused-place-pin")
                assertTrue(opened != requireNotNull(pin.firstOrNull()?.id()))
                assertTrue(overviewZoom < firstZoom - 1.0)
                assertTrue(abs(firstZoom - finish.zoom) < 0.01)
                Log.i("SS09Verification", "random flight $firstCenter -> $center, zoom $firstZoom -> $overviewZoom -> ${finish.zoom}")
            }
        } finally {
            compose.runOnUiThread { map.removeOnCameraMoveListener(observeZoom) }
        }
    }

    @Test fun ss09ButtonsChangeZoomAndTheNativeMapStillReceivesGestures() = withOpenMap { view, map ->
        fun zoom() = compose.runOnUiThread { map.cameraPosition.zoom }
        fun awaitZoom(value: Double) = compose.waitUntil(15_000) { abs(zoom() - value) < 0.01 }
        val increase = compose.onNodeWithContentDescription(text(PresentationR.string.map_zoom_increase))
        val decrease = compose.onNodeWithContentDescription(text(PresentationR.string.map_zoom_decrease))
        val initial = zoom()
        increase.performClick()
        awaitZoom(initial + 1)
        val increased = zoom()
        decrease.performClick()
        awaitZoom(initial)
        Log.i("SS09Verification", "camera.zoom buttons: $initial -> $increased -> ${zoom()}")
        compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.zoomTo(map.maxZoomLevel)) }
        awaitZoom(compose.runOnUiThread { map.maxZoomLevel })
        increase.assertIsNotEnabled()
        compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.zoomTo(map.minZoomLevel)) }
        awaitZoom(compose.runOnUiThread { map.minZoomLevel })
        decrease.assertIsNotEnabled()
        compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.zoomTo(initial)) }
        awaitZoom(initial)

        val canvas = compose.onNodeWithTag("ss09-map")
        canvas.performTouchInput { doubleClick(Offset(center.x / 2, center.y / 2)) }
        awaitZoom(initial + 1)
        val doubleTapZoom = zoom()
        assertTrue(compose.runOnUiThread { map.uiSettings.isZoomGesturesEnabled })
        SystemClock.sleep(400)
        val center = mapCenter(view)
        val near = Offset(view.width * 0.10f, 0f)
        val far = Offset(view.width * 0.40f, 0f)
        var multitouchMoves = 0
        compose.runOnUiThread {
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 2) multitouchMoves++
                false
            }
        }
        moveTouch(listOf(center - near, center + near), listOf(center - far, center + far))
        try {
            compose.waitUntil(15_000) { zoom() > doubleTapZoom + 0.1 }
        } finally {
            Log.i("SS09Verification", "pinch pointer moves=${compose.runOnUiThread { multitouchMoves }}, zoom=${zoom()}")
            compose.runOnUiThread { view.setOnTouchListener(null) }
        }
        Log.i("SS09Verification", "camera.zoom gestures: $initial -> $doubleTapZoom -> ${zoom()}")
    }

    @Test fun ss09LayersAndFiltersOpenIndependentlyAndPanningHasNoRefreshBanner() = withOpenMap { view, map ->
        compose.onNodeWithContentDescription(text(PresentationR.string.map_filters)).performClick()
        compose.onNodeWithText(text(PresentationR.string.map_filters_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.map_layers)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(PresentationR.string.map_card_close)).performClick()
        compose.onNodeWithText(text(PresentationR.string.map_filters_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(text(PresentationR.string.map_layer_public)).performClick()
        compose.onNodeWithText(text(PresentationR.string.map_layers)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.map_layer_cities)).performClick()
        compose.onNodeWithText(text(PresentationR.string.map_panel_done)).performClick()
        compose.onNodeWithText(text(PresentationR.string.map_layer_cities)).assertIsDisplayed()
        repeat(3) {
            val before = compose.runOnUiThread { requireNotNull(map.cameraPosition.target).longitude }
            val center = mapCenter(view)
            moveTouch(listOf(center), listOf(center + Offset(145f, 90f)))
            compose.waitUntil(10_000) {
                abs(compose.runOnUiThread { requireNotNull(map.cameraPosition.target).longitude } - before) > 0.01
            }
            compose.onNodeWithText("Обновляем музыкальные места…").assertDoesNotExist()
        }
        compose.onNodeWithText(text(PresentationR.string.nav_map)).assertIsSelected()
    }

    private fun mapCenter(view: MapView): Offset = compose.runOnUiThread {
        val screen = IntArray(2)
        view.getLocationOnScreen(screen)
        Offset(screen[0] + view.width / 2f, screen[1] + view.height / 2f)
    }

    private fun moveTouch(start: List<Offset>, end: List<Offset>) {
        val input = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, points: List<Offset>) {
            val pointers = Array(points.size) { index ->
                MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coords = Array(points.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = points[index].x
                    y = points[index].y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
                pointers, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue(input.injectInputEvent(event, false))
            } finally {
                event.recycle()
            }
        }

        send(MotionEvent.ACTION_DOWN, start.take(1))
        if (start.size == 2) send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start)
        val steps = 16
        for (step in 1..steps) {
            SystemClock.sleep(16)
            val progress = step.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, start.zip(end) { from, to -> from + (to - from) * progress })
        }
        if (start.size == 2) send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
        send(MotionEvent.ACTION_UP, end.take(1))
    }

    private fun withOpenMap(test: (MapView, MapLibreMap) -> Unit) {
        assumeTrue(BuildConfig.MAPTILER_API_KEY.isNotBlank())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText(text(PresentationR.string.nav_map)).performClick()
            compose.onNodeWithText(text(PresentationR.string.map_title)).assertIsDisplayed()
            val view = activeMap(scenario)
            var ready: MapLibreMap? = null
            compose.runOnUiThread { view.getMapAsync { ready = it } }
            compose.waitUntil(45_000) { compose.runOnUiThread { ready?.style?.isFullyLoaded == true } }
            test(view, requireNotNull(ready))
        }
    }

    private fun tapMap(view: MapView, position: PointF) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, position.x, position.y, 0)
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun activeMap(scenario: ActivityScenario<MainActivity>): MapView {
        var map: MapView? = null
        scenario.onActivity { map = findMap(it.window.decorView) }
        return requireNotNull(map)
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findMap(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Test fun profileActionOpensProfileAndBackRestoresHome() {
        setContent()
        compose.runOnIdle { requireNotNull(dispatch)(HomeAction.Profile) }
        compose.onNodeWithText(text(PresentationR.string.profile_mine)).assertIsDisplayed()
        compose.onNodeWithText(text(PresentationR.string.nav_library)).assertIsSelected()
        pressBack()
        compose.onNodeWithTag("home-feed").assertIsDisplayed()
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

class GuestProfileNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun guestCanOpenPersonalProfileAndReachRealSignInForm() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val title = text(PresentationR.string.profile_sign_in_title)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("home-feed")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(text(PresentationR.string.home_profile))
                .assertIsDisplayed().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(text(PresentationR.string.profile_sign_in)).performClick()
            compose.onNodeWithContentDescription(text(PresentationR.string.profile_email)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(PresentationR.string.profile_password)).assertIsDisplayed()
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
