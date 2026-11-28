package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.GeneratedItem
import com.soundscape.app.core.domain.model.GeneratedPlaylistDraft
import com.soundscape.app.core.domain.model.GenerationRequest
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistInput
import com.soundscape.app.core.domain.model.TrackAvailability
import com.soundscape.app.core.domain.repository.PlaylistGenerator
import com.soundscape.app.core.domain.repository.PlaylistRepository
import com.soundscape.app.core.domain.repository.TrackMetadata
import com.soundscape.app.core.domain.repository.TrackMetadataReader
import com.soundscape.app.core.network.GeneratedPlaylistDto
import com.soundscape.app.core.network.GenerationDto
import com.soundscape.app.core.network.GenerationStopDto
import com.soundscape.app.core.network.GetPlaylistRequestDto
import com.soundscape.app.core.network.PlaylistGenerationApi
import com.soundscape.app.core.network.SavePlaylistDto
import dagger.Lazy
import java.util.UUID
import javax.inject.Inject

private const val INVALID_INPUT = 422
private const val MAX_METADATA_IDS = 100
private const val MAX_TITLE_LENGTH = 150
private const val MAX_DESCRIPTION_LENGTH = 2000
private const val MAX_GENERATED_TRACKS = 50

class RemotePlaylistGeneration @Inject constructor(
    private val api: Lazy<PlaylistGenerationApi>,
    private val remote: RemoteCall,
    private val session: SupabaseProfileSession,
) : PlaylistGenerator, PlaylistRepository, TrackMetadataReader {
    override suspend fun generate(request: GenerationRequest): DomainResult<GeneratedPlaylistDraft> {
        val dto = request.toDto()
        return session.authorizedOnce { credential ->
            remote.execute({ api.get().generate(dto, credential) }, ::profileSessionError) { it.toDraft() }
        }
    }

    override suspend fun get(id: UUID): DomainResult<Playlist> = session.authorizedOnce { credential ->
        remote.execute(
            { api.get().getOwned(GetPlaylistRequestDto(id.toString()), credential) },
            ::profileSessionError,
        ) {
            it.toDomain().also { playlist -> require(playlist.id == id) }
        }
    }

    override suspend fun create(id: UUID, input: PlaylistInput) = save(id, input, null)

    override suspend fun update(id: UUID, expectedRevision: Long, input: PlaylistInput) =
        save(id, input, expectedRevision)

    private suspend fun save(id: UUID, input: PlaylistInput, revision: Long?): DomainResult<Playlist> {
        val dto = input.toSaveDto(id, revision)
        return session.authorizedOnce { credential ->
            remote.execute({ api.get().save(dto, credential) }, ::playlistSaveError) {
                it.toDomain().also { playlist -> require(playlist.id == id) }
            }
        }
    }

    override suspend fun read(ids: List<UUID>): DomainResult<List<TrackMetadata>> {
        if (ids.isEmpty()) return DomainResult.Success(emptyList())
        require(ids.size <= MAX_METADATA_IDS)
        return remote.execute({ api.get().metadata("in.(${ids.distinct().joinToString(",")})") }) { rows ->
            rows.map { row ->
                val id = parseGeneratedId(row.id)
                require(id in ids && row.durationMs >= 0 && row.title.isNotBlank())
                val artists = row.credits.sortedBy { it.order }.mapNotNull { it.artists?.name }
                    .distinct().joinToString(", ").ifBlank { null }
                TrackMetadata(id, row.title, artists, row.durationMs)
            }
        }
    }
}

private fun playlistSaveError(status: Int): DomainError? =
    if (status == INVALID_INPUT) DomainError.Validation("playlist", "invalid_request") else profileSessionError(status)

internal fun GenerationRequest.toDto(): GenerationDto {
    val base = GenerationDto(mode = "", count = options.count, onlyInApp = options.onlyInApp,
        genre = options.genre, mood = options.mood, genreIds = options.genreIds, moodIds = options.moodIds)
    return when (this) {
        is GenerationRequest.Quick -> base.copy(mode = "QUICK", prompt = prompt)
        is GenerationRequest.Advanced -> base.copy(mode = "ADVANCED", prompt = prompt)
        is GenerationRequest.Place -> base.copy(
            mode = "PLACE", placeId = placeId?.toString(), musicPinId = musicPinId?.toString(), prompt = prompt,
        )
        is GenerationRequest.Journey -> base.copy(
            mode = "JOURNEY",
            stops = stops.mapIndexed { index, stop ->
                GenerationStopDto(stop.id.toString(), stop.kind.name, index + 1)
            },
        )
    }
}

internal fun PlaylistInput.toSaveDto(id: UUID, revision: Long?) = SavePlaylistDto(
    id.toString(), title, visibility.name, trackIds.map(UUID::toString),
    description, revision, genreIds, tagIds, coverPath,
)

internal fun GeneratedPlaylistDto.toDraft(): GeneratedPlaylistDraft {
    require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH && description.length <= MAX_DESCRIPTION_LENGTH)
    require(items.size in 1..MAX_GENERATED_TRACKS)
    val ordered = items.sortedBy { it.position }
    require(ordered.map { it.position } == (1..ordered.size).toList())
    val tracks = ordered.map {
        GeneratedItem(parseGeneratedId(it.trackId), it.reason, it.availability?.let(TrackAvailability::valueOf))
    }
    require(tracks.map { it.trackId }.distinct().size == tracks.size)
    return GeneratedPlaylistDraft(title, description, tracks, warnings)
}

private fun parseGeneratedId(value: String): UUID =
    UUID.fromString(value).also { require(it.toString().equals(value, ignoreCase = true)) }
