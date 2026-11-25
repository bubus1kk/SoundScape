package com.soundscape.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import com.soundscape.app.navigation.AppBottomBar
import com.soundscape.app.navigation.AppDestination
import com.soundscape.app.navigation.GENERATOR_ROUTE
import com.soundscape.app.navigation.installWorkshopRoutes
import com.soundscape.app.presentation.generation.GenerationForm
import com.soundscape.app.presentation.generation.GenerationMode
import com.soundscape.app.presentation.generation.PlaylistGeneratorScreen
import com.soundscape.app.presentation.generation.PlaylistWorkshopViewModel
import com.soundscape.app.presentation.generation.WorkshopState
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaylistWorkshopTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigatesEditsRecoversUncertainSaveAndPreservesDraft() {
        val fixture = Fixture()
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        val model = fixture.model(saved)
        compose.runOnUiThread { store.put("workshop", model) }
        compose.setContent {
            SoundScapeTheme {
                val controller = rememberNavController()
                Scaffold(bottomBar = { AppBottomBar(AppDestination.Music.route) {} }) { padding ->
                    NavHost(controller, GENERATOR_ROUTE, Modifier.padding(padding)) {
                        installWorkshopRoutes(controller, null, modelFor = { model }, signIn = {})
                    }
                }
            }
        }
        waitFor("Генератор плейлистов")
        screenshot("SS14-fixture")
        compose.onNodeWithText("Твой запрос *").performScrollTo().performTextReplacement("Ночная поездка")
        closeSoftKeyboard()
        compose.onNodeWithText("Сгенерировать плейлист").performClick()
        waitFor("Результат генерации")
        waitFor("1. Fantasia Blu (lofi house mix)", scroll = true)
        assertEquals(1, fixture.generations)
        assertEquals("Ночная поездка", (fixture.request as GenerationRequest.Quick).prompt)
        screenshot("SS15-fixture")
        compose.onNodeWithText("Изменить").performScrollTo().performClick()
        compose.onNodeWithText("Название плейлиста").performScrollTo().performTextReplacement("Изменённая подборка")
        compose.onNodeWithText("Описание").performScrollTo().performTextReplacement("Описание после редактирования")
        closeSoftKeyboard()
        compose.onNodeWithText("Готово").performScrollTo().performClick()
        compose.onNodeWithText("Изменить порядок").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Выше: lofi type beat").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Удалить: Fantasia Blu (lofi house mix)").performScrollTo().performClick()
        compose.onNodeWithText("Готово").performScrollTo().performClick()
        compose.onNodeWithText("Добавить из каталога").performScrollTo().performClick()
        compose.onNodeWithText("Поиск по названию или исполнителю").performTextReplacement("Love me")
        waitFor(fixture.extra.title)
        compose.onNodeWithText(fixture.extra.title).performClick()
        compose.onNodeWithTag("workshop-lookup-done").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        waitFor("Выйти без сохранения?")
        compose.onNodeWithText("Вернуться к запросу").performClick()
        waitFor("Генератор плейлистов")
        compose.onNodeWithText("Вернуться к результату").performScrollTo().performClick()
        waitFor("Результат генерации")
        assertEquals(1, fixture.generations)

        compose.runOnIdle {
            val restored = fixture.model(saved)
            val restoredStore = ViewModelStore().apply { put("restored", restored) }
            val draft = requireNotNull(restored.state.value.draft)
            assertEquals("Изменённая подборка", draft.title)
            assertEquals("Описание после редактирования", draft.description)
            assertEquals(listOf(fixture.first, fixture.extra.id), draft.input().trackIds)
            assertEquals("Ночная поездка", restored.state.value.form.quick)
            restoredStore.clear()
        }
        compose.onNodeWithText("Сохранить плейлист").performClick()
        compose.onNodeWithText("Подтвердить сохранение").performClick()
        waitFor("Проверить сохранение")
        assertEquals(1, fixture.creates)
        compose.runOnIdle { model.save(); model.save() }
        waitFor("Плейлист сохранён")
        assertEquals(1, fixture.creates)
        assertEquals(1, fixture.reads)
        assertEquals("Изменённая подборка", fixture.received?.title)
        assertEquals("Описание после редактирования", fixture.received?.description)
        assertEquals(listOf(fixture.first, fixture.extra.id), fixture.received?.trackIds)
        assertEquals(1, fixture.generations)
        compose.runOnIdle { store.clear() }
    }

    @Test fun capturesGeneratorVisualStatesWithoutProductionCalls() {
        val fixture = Fixture()
        val model = fixture.model(SavedStateHandle())
        val paris = SearchHit(SearchCategory.PLACE, UUID(0, 41), "Париж", "Франция", null, null)
        val berlin = SearchHit(SearchCategory.PLACE, UUID(0, 42), "Берлин", "Германия", null, null)
        val quick = GenerationForm(quick = "Музыка для ночной поездки по городу")
        val base = WorkshopState(quick, null, fixture.accountId.value, sessionReady = true)
        val states = listOf(
            "quick-empty" to base.copy(form = quick.copy(quick = "")),
            "quick-filled" to base,
            "advanced" to base.copy(form = quick.copy(mode = GenerationMode.ADVANCED, advanced = quick.quick,
                genre = SearchOption(1, "Электроника"), mood = SearchOption(1, "Ночное"), onlyInApp = true)),
            "place-empty" to base.copy(form = quick.copy(mode = GenerationMode.PLACE)),
            "place-filled" to base.copy(form = quick.copy(mode = GenerationMode.PLACE, place = paris)),
            "journey" to base.copy(form = quick.copy(mode = GenerationMode.JOURNEY, stops = listOf(paris, berlin))),
            "loading" to base.copy(generating = true),
            "error" to base.copy(generationError = DomainError.Server),
            "no-candidates" to base.copy(generationError = DomainError.Validation("tracks", "no_candidates")),
            "quota" to base.copy(generationError = DomainError.RateLimited()),
            "offline" to base,
            "auth" to base.copy(account = null, generationError = DomainError.Unauthenticated),
        )
        val index = mutableStateOf(0)
        val chrome = mutableStateOf(true)
        val store = ViewModelStore()
        compose.runOnUiThread { store.put("generator", model) }
        compose.setContent {
            SoundScapeTheme {
                val (name, state) = states[index.value]
                Scaffold(bottomBar = { if (chrome.value) AppBottomBar(AppDestination.Music.route) {} }) { padding ->
                    key(name) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                            PlaylistGeneratorScreen(state, model, name == "offline", {}, {}, {}, { chrome.value = it })
                        }
                    }
                }
            }
        }
        states.forEachIndexed { i, (name, _) ->
            compose.runOnIdle { index.value = i }
            waitFor(when (name) {
                "offline" -> "Нет подключения к интернету"
                "auth" -> "Необходимо войти в аккаунт"
                "quota" -> "Лимит генераций исчерпан"
                else -> "Генератор плейлистов"
            })
            val heading = when (name) {
                "advanced" -> "Расширенный запрос"
                "place-empty", "place-filled" -> "Музыка по месту"
                "journey" -> "Музыка по маршруту"
                else -> null
            }
            if (heading != null) {
                val pixels = when (name) {
                    "advanced" -> 470f
                    "place-empty" -> 275f
                    "place-filled" -> 270f
                    else -> 440f
                }
                compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(heading)))
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
            }
            compose.waitForIdle()
            android.os.SystemClock.sleep(300)
            if (InstrumentationRegistry.getArguments().getString("ss14Visual") == "true") screenshot("SS14-$name")
        }
        assertEquals(0, fixture.generations)
        compose.runOnIdle { store.clear() }
    }

    private fun waitFor(label: String, scroll: Boolean = false) {
        compose.waitUntil(8_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(label)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed()
    }

    private class Fixture : ProfileSession, PlaylistGenerator, PlaylistRepository, GlobalSearchRepository, TrackMetadataReader {
        private val owner = UUID(0, 17)
        val first = UUID.fromString("c34008ef-f3de-466f-ba64-333011dd15fe")
        private val second = UUID.fromString("a0dfd280-b98e-4836-9363-8a884ea21480")
        val extra = SearchHit(SearchCategory.TRACK, UUID.fromString("69fc9ed9-3b57-490a-8003-643b02c705a1"),
            "Lofi Type Beat  Love me", "Beats Love", null, TrackAvailability.EXTERNAL_ONLY)
        private val tracks = listOf(
            Track(first, "lofi type beat", "[bsdu]", 334.seconds, TrackAvailability.EXTERNAL_ONLY),
            Track(second, "Fantasia Blu (lofi house mix)", "Blake Tree", 258.seconds, TrackAvailability.EXTERNAL_ONLY),
            Track(extra.id, extra.title, "Beats Love", 191.seconds, TrackAvailability.EXTERNAL_ONLY),
        ).associateBy { it.id }
        override val accountId = MutableStateFlow<UUID?>(owner)
        override val history = MutableStateFlow(emptyList<String>())
        var generations = 0
        var request: GenerationRequest? = null
        var creates = 0
        var reads = 0
        var received: PlaylistInput? = null
        private var stored: Playlist? = null

        fun model(handle: SavedStateHandle) =
            PlaylistWorkshopViewModel(handle, this, this, this, this, this)

        override suspend fun restore() = Unit
        override suspend fun signOut() { accountId.value = null }
        override suspend fun currentUser() = DomainResult.Success(UserProfile(owner, "fixture"))
        override suspend fun signIn(email: String, password: String) = currentUser()

        override suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> {
            generations++
            this.request = request
            return DomainResult.Success(GeneratedPlaylistDraft("Тестовая подборка", "Ответ тестового каталога",
                listOf(GeneratedItem(second), GeneratedItem(first)), listOf("Проверочный ответ")))
        }

        override suspend fun create(id: UUID, input: PlaylistInput): DomainResult<Playlist> {
            creates++
            received = input
            stored = Playlist(id, owner, input.title, input.visibility, input.trackIds.map { trackId ->
                PlaylistItem(tracks.getValue(trackId))
            }, 1, description = input.description)
            return DomainResult.Failure(DomainError.Network)
        }

        override suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput): DomainResult<Playlist> =
            DomainResult.Failure(DomainError.VersionConflict)

        override suspend fun get(id: UUID): DomainResult<Playlist> {
            reads++
            return stored?.let { DomainResult.Success(it) } ?: DomainResult.Failure(DomainError.NotFound)
        }

        override suspend fun rememberQuery(query: String) = Unit
        override suspend fun clearHistory() = Unit
        override suspend fun options(facet: SearchFacet) = DomainResult.Success(emptyList<SearchOption>())
        override suspend fun search(request: GlobalSearch, limit: Int, offset: Int) =
            DomainResult.Success(Page(listOf(extra), false))

        override suspend fun read(ids: List<UUID>) = DomainResult.Success(ids.map {
            val track = tracks.getValue(it)
            TrackMetadata(it, track.title, track.artist, track.duration.inWholeMilliseconds)
        })
    }
}

class PlaylistWorkshopNavigationTest {
    // MainActivity is the only activity hosting Compose and receiving the injected touches.
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun opensGeneratorFromHomeAndRestoresPromptAfterActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.waitUntil(8_000) {
                compose.onAllNodes(hasTestTag("home-feed")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("home-feed").performScrollToNode(hasText("Создать плейлист"))
            compose.onNodeWithText("Создать плейлист").assertIsDisplayed().performClick()
            waitFor("Генератор плейлистов")
            compose.onNodeWithText("Твой запрос *").performScrollTo().performTextReplacement("Мелодии для прогулки")
            closeSoftKeyboard()
            compose.onNodeWithText("Расширенный").performScrollTo().performClick()
            waitFor("Расширенный запрос")
            compose.onNodeWithText("По месту").performScrollTo().performClick()
            waitFor("Музыка по месту")
            compose.onNodeWithText("Выбрать место").performScrollTo().performClick()
            waitFor("Название места")
            compose.onNodeWithTag("workshop-lookup-done").performClick()
            compose.onNodeWithText("По маршруту").performScrollTo().performClick()
            waitFor("Музыка по маршруту")
            compose.onNodeWithText("Быстрый").performScrollTo().performClick()
            activity.recreate()
            waitFor("Генератор плейлистов")
            waitFor("Мелодии для прогулки")
            screenshot("SS14-app")
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(8_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).assertIsDisplayed()
    }
}

private fun screenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val image = instrumentation.uiAutomation.takeScreenshot()
    if (image != null) {
        File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
