package com.soundscape.app.presentation.generation

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.repository.PlaylistRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.domain.repository.TrackMetadata
import com.soundscape.app.core.domain.usecase.SavePlaylistUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WorkshopSave(
    private val session: ProfileSession,
    private val playlists: PlaylistRepository,
    private val mutable: MutableStateFlow<WorkshopState>,
    private val scope: CoroutineScope,
    private val storeDraft: (WorkshopDraft) -> Unit,
) {
    private val savePlaylist = SavePlaylistUseCase(session, playlists)

    fun save() {
        val snapshot = mutable.value
        val draft = snapshot.draft?.takeUnless { snapshot.saving || it.confirmed || !it.valid } ?: return
        if (snapshot.account != draft.owner) {
            mutable.update { it.copy(saveError = DomainError.Unauthenticated) }
            return
        }
        mutable.update { it.copy(saving = true, saveError = null) }
        scope.launch {
            try {
                saveOrReconcile(draft)
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun saveOrReconcile(draft: WorkshopDraft) {
        val input = draft.input()
        if (draft.uncertain && reconcile(draft, input)) return
        if (session.accountId.value != draft.owner) {
            mutable.update { it.copy(saveError = DomainError.Unauthenticated) }
            return
        }
        storeDraft(draft.copy(uncertain = true))
        when (val result = savePlaylist(draft.id, input, draft.revision)) {
            is DomainResult.Success -> confirm(draft, result.value)
            is DomainResult.Failure -> {
                when (result.error) {
                    DomainError.Network, DomainError.Timeout, DomainError.Server, DomainError.Unexpected,
                    DomainError.AlreadyExists -> mutable.update { it.copy(saveError = result.error) }
                    else -> rejected(draft, result.error)
                }
            }
        }
    }

    // An absent first revision is the only lookup outcome that allows another save attempt.
    private suspend fun reconcile(draft: WorkshopDraft, input: PlaylistInput): Boolean =
        when (val check = playlists.get(draft.id)) {
            is DomainResult.Success -> {
                val actual = check.value
                if (matchesSavedRevision(actual, draft, input)) confirm(draft, actual)
                else rejected(draft, DomainError.VersionConflict)
                true
            }
            is DomainResult.Failure -> {
                val handled = check.error != DomainError.NotFound || draft.revision != null
                if (handled) mutable.update { it.copy(saveError = check.error) }
                handled
            }
        }

    private fun rejected(draft: WorkshopDraft, error: DomainError) {
        storeDraft(draft.copy(uncertain = false))
        mutable.update { it.copy(saveError = error) }
    }

    private fun confirm(draft: WorkshopDraft, playlist: Playlist) {
        if (playlist.ownerId != draft.owner || playlist.isEditorial) {
            rejected(draft, DomainError.VersionConflict)
            return
        }
        val reasons = draft.tracks.associate { it.id to it.reason }
        val metadata = playlist.items.associate { item ->
            val track = item.track
            track.id to TrackMetadata(track.id, track.title, track.artist, track.duration.inWholeMilliseconds)
        }
        storeDraft(draft.copy(title = playlist.title, description = playlist.description.orEmpty(),
            visibility = playlist.visibility, tracks = playlist.items.map {
                EditableTrack(it.track.id, reasons[it.track.id], it.track.availability)
            }, revision = playlist.revision, confirmed = true, uncertain = false))
        mutable.update { it.copy(metadata = it.metadata + metadata, saveError = null) }
    }
}

private fun matchesSavedRevision(actual: Playlist, draft: WorkshopDraft, input: PlaylistInput): Boolean {
    val ownedRevision = actual.ownerId == draft.owner &&
        !actual.isEditorial &&
        actual.revision == (draft.revision ?: 0) + 1
    val sameDetails = actual.title == input.title &&
        actual.description.orEmpty() == input.description.orEmpty() &&
        actual.visibility == input.visibility
    return ownedRevision && sameDetails && actual.items.map { it.track.id } == input.trackIds
}
