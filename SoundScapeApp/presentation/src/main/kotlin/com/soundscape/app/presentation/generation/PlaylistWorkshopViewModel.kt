package com.soundscape.app.presentation.generation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GenerationOptions
import com.soundscape.app.core.domain.model.GenerationRequest
import com.soundscape.app.core.domain.model.JourneyStop
import com.soundscape.app.core.domain.model.JourneyStopKind
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.GlobalSearchRepository
import com.soundscape.app.core.domain.repository.PlaylistGenerator
import com.soundscape.app.core.domain.repository.PlaylistRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.domain.repository.SearchCategory
import com.soundscape.app.core.domain.repository.SearchFacet
import com.soundscape.app.core.domain.repository.SearchHit
import com.soundscape.app.core.domain.repository.SearchOption
import com.soundscape.app.core.domain.repository.TrackMetadata
import com.soundscape.app.core.domain.repository.TrackMetadataReader
import com.soundscape.app.core.domain.usecase.GeneratePlaylistUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val MAX_JOURNEY_STOPS = 50
internal const val MAX_DRAFT_TRACKS = 100
internal const val MAX_DRAFT_TITLE_LENGTH = 150
internal const val MAX_DRAFT_DESCRIPTION_LENGTH = 2000
internal const val DEFAULT_TRACK_COUNT = 20
private const val QUOTA_RETRY_MS = 30_000L

enum class GenerationMode { QUICK, ADVANCED, PLACE, JOURNEY }

data class GenerationForm(
    val mode: GenerationMode = GenerationMode.QUICK,
    val quick: String = "",
    val advanced: String = "",
    val placePrompt: String = "",
    val place: SearchHit? = null,
    val stops: List<SearchHit> = emptyList(),
    val count: Int = DEFAULT_TRACK_COUNT,
    val genre: SearchOption? = null,
    val mood: SearchOption? = null,
    val onlyInApp: Boolean = false,
) {
    fun request(): GenerationRequest? =
        when (mode) {
            GenerationMode.QUICK ->
                quick.trim().takeIf { it.isNotEmpty() }?.let { GenerationRequest.Quick(it) }
            GenerationMode.ADVANCED ->
                if (advanced.isBlank() && genre == null && mood == null) null
                else
                    GenerationRequest.Advanced(
                        advanced.trim().ifBlank { null },
                        GenerationOptions(
                            count,
                            onlyInApp,
                            genre?.name,
                            mood?.name,
                            listOfNotNull(genre?.id),
                            listOfNotNull(mood?.id),
                        ),
                    )
            GenerationMode.PLACE ->
                place?.let {
                    GenerationRequest.Place(it.id, prompt = placePrompt.trim().ifBlank { null })
                }
            GenerationMode.JOURNEY ->
                stops
                    .takeIf { it.size in 2..MAX_JOURNEY_STOPS }
                    ?.let { places ->
                        GenerationRequest.Journey(
                            places.map { JourneyStop(it.id, JourneyStopKind.PLACE) }
                        )
                    }
        }
}

data class EditableTrack(val id: UUID, val reason: String? = null, val availability: TrackAvailability? = null)

data class WorkshopDraft(
    val id: UUID,
    val owner: UUID,
    val title: String,
    val description: String,
    val tracks: List<EditableTrack>,
    val warnings: List<String>,
    val visibility: Visibility = Visibility.PRIVATE,
    val context: String? = null,
    val uncertain: Boolean = false,
    val revision: Long? = null,
    val confirmed: Boolean = false,
) {
    val valid get() = title.isNotBlank() &&
        title.length <= MAX_DRAFT_TITLE_LENGTH &&
        description.length <= MAX_DRAFT_DESCRIPTION_LENGTH &&
        tracks.size in 1..MAX_DRAFT_TRACKS &&
        tracks.map { it.id }.distinct().size == tracks.size

    fun input() = PlaylistInput(title.trim(), visibility, tracks.map { it.id }, description.trim().ifBlank { null })
}

data class WorkshopState(
    val form: GenerationForm,
    val draft: WorkshopDraft?,
    val account: UUID? = null,
    val sessionReady: Boolean = false,
    val generating: Boolean = false,
    val saving: Boolean = false,
    val generationError: DomainError? = null,
    val saveError: DomainError? = null,
    val metadata: Map<UUID, TrackMetadata> = emptyMap(),
    val metadataLoading: Boolean = false,
    val metadataError: Boolean = false,
    val options: Map<SearchFacet, List<SearchOption>> = emptyMap(),
    val optionErrors: Set<SearchFacet> = emptySet(),
    val lookup: LookupState = LookupState(),
)

data class LookupState(
    val items: List<SearchHit> = emptyList(),
    val loading: Boolean = false,
    val error: DomainError? = null,
    val hasMore: Boolean = false,
)

@HiltViewModel
class PlaylistWorkshopViewModel internal constructor(
    private val persistence: WorkshopPersistence,
    private val session: ProfileSession,
    generator: PlaylistGenerator,
    playlists: PlaylistRepository,
    catalog: GlobalSearchRepository,
    tracks: TrackMetadataReader,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    @Inject constructor(
        saved: SavedStateHandle,
        session: ProfileSession,
        generator: PlaylistGenerator,
        playlists: PlaylistRepository,
        catalog: GlobalSearchRepository,
        tracks: TrackMetadataReader,
    ) : this(SavedWorkshopState(saved), session, generator, playlists, catalog, tracks)

    private val generatePlaylist = GeneratePlaylistUseCase(session, generator)
    private val mutable = MutableStateFlow(WorkshopState(persistence.form, persistence.draft))
    val state = mutable.asStateFlow()
    internal val catalogActions = WorkshopCatalog(catalog, tracks, mutable, viewModelScope)
    private val saveAction = WorkshopSave(session, playlists, mutable, viewModelScope, ::storeDraft)
    private var quotaUntil = 0L

    init {
        viewModelScope.launch {
            session.restore()
            session.accountId.collect { account ->
                mutable.update {
                    val signedIn = account != null && it.account == null
                    it.copy(
                        account = account,
                        sessionReady = true,
                        generationError =
                            it.generationError.takeUnless { error ->
                                signedIn && error == DomainError.Unauthenticated
                            },
                        saveError =
                            it.saveError.takeUnless { error ->
                                signedIn && error == DomainError.Unauthenticated
                            },
                    )
                }
            }
        }
        state.value.draft?.let { catalogActions.refreshMetadata() }
    }

    fun form(change: (GenerationForm) -> GenerationForm) {
        if (state.value.generating) return
        val value = change(state.value.form)
        persistence.form = value
        mutable.update { it.copy(form = value, generationError = null) }
    }

    fun generate(ready: () -> Unit) {
        val snapshot = state.value
        val request = snapshot.form.request()
        val owner = snapshot.account
        val busy = snapshot.generating || snapshot.draft?.uncertain == true
        if (request == null || owner == null || busy) return
        if (now() < quotaUntil) {
            mutable.update { it.copy(generationError = DomainError.RateLimited()) }
            return
        }
        mutable.update { it.copy(generating = true, generationError = null) }
        viewModelScope.launch {
            try {
                when (val result = generatePlaylist(request)) {
                    is DomainResult.Success -> if (session.accountId.value == owner) {
                        val value = result.value
                        val context = when (request) {
                            is GenerationRequest.Place -> snapshot.form.place?.title
                            is GenerationRequest.Journey -> snapshot.form.stops.joinToString(" → ") { it.title }
                            else -> null
                        }
                        storeDraft(WorkshopDraft(UUID.randomUUID(), owner, value.title, value.description,
                            value.items.map { EditableTrack(it.trackId, it.reason, it.availability) },
                            value.warnings, context = context))
                        mutable.update { it.copy(saveError = null, metadata = emptyMap()) }
                        catalogActions.refreshMetadata()
                        ready()
                    }
                    is DomainResult.Failure -> {
                        val error = result.error
                        if (error is DomainError.RateLimited) {
                            quotaUntil = now() +
                                (error.retryAfter?.inWholeMilliseconds ?: QUOTA_RETRY_MS)
                        }
                        mutable.update { it.copy(generationError = error) }
                    }
                }
            } finally {
                mutable.update { it.copy(generating = false) }
            }
        }
    }

    fun dismissGenerationError() = mutable.update { it.copy(generationError = null) }

    fun edit(change: (WorkshopDraft) -> WorkshopDraft) {
        val value = state.value.draft ?: return
        if (state.value.saving || value.confirmed || value.uncertain) return
        storeDraft(change(value))
        mutable.update { it.copy(saveError = null) }
    }

    fun editConfirmed() {
        val value = state.value.draft ?: return
        if (value.confirmed && state.value.account == value.owner) storeDraft(value.copy(confirmed = false))
    }

    fun discard() {
        val value = state.value.draft ?: return
        if (state.value.saving || value.uncertain) return
        catalogActions.cancelMetadata()
        persistence.draft = null
        mutable.update { it.copy(draft = null, metadata = emptyMap(), saveError = null, metadataLoading = false) }
    }

    fun add(hit: SearchHit) {
        val previous = state.value.draft ?: return
        if (hit.category != SearchCategory.TRACK ||
            previous.tracks.any { it.id == hit.id } ||
            previous.tracks.size >= MAX_DRAFT_TRACKS
        ) return
        edit { it.copy(tracks = it.tracks + EditableTrack(hit.id, availability = hit.availability)) }
        if (previous != state.value.draft) catalogActions.refreshMetadata()
    }

    fun save() = saveAction.save()

    private fun storeDraft(draft: WorkshopDraft) {
        persistence.draft = draft
        mutable.update { it.copy(draft = draft) }
    }
}

fun <T> List<T>.reordered(index: Int, destination: Int): List<T> =
    if (index !in indices || destination !in indices) this
    else toMutableList().apply { add(destination, removeAt(index)) }
