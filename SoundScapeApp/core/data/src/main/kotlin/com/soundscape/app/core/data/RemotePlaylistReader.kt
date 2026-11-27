package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.Playlist
import com.soundscape.app.core.domain.model.PlaylistItem
import com.soundscape.app.core.domain.model.Visibility
import com.soundscape.app.core.domain.repository.PlaylistReader
import com.soundscape.app.core.network.GetPlaylistRequestDto
import com.soundscape.app.core.network.PlaylistDetailDto
import com.soundscape.app.core.network.PlaylistsApi
import dagger.Lazy
import java.util.UUID
import javax.inject.Inject

private fun playlistUuid(value: String): UUID =
    UUID.fromString(value).also {
        require(it.toString().equals(value, ignoreCase = true))
    }

internal fun PlaylistDetailDto.toDomain(): Playlist {
    require(items.all { it.position > 0 })
    require(items.map { it.position }.distinct().size == items.size)

    val playlist = Playlist(
        id = playlistUuid(id),
        ownerId = playlistUuid(ownerId),
        title = title,
        visibility = Visibility.valueOf(visibility),
        items = items.sortedBy { it.position }.map {
            PlaylistItem(track = it.track.toDomain())
        },
        revision = revision,
        description = description,
        coverPath = coverPath,
        genreIds = genreIds,
        tagIds = tagIds,
        placeId = placeId?.let(::playlistUuid),
        isEditorial = isEditorial,
    )
    require(playlist.validateForRead() is DomainResult.Success)
    return playlist
}

class RemotePlaylistReader @Inject constructor(
    private val api: Lazy<PlaylistsApi>,
    private val call: RemoteCall,
) : PlaylistReader {
    override suspend fun get(id: UUID): DomainResult<Playlist> =
        call.execute(
            request = {
                api.get().get(GetPlaylistRequestDto(playlistId = id.toString()))
            },
            map = {
                it.toDomain().also { playlist ->
                    require(playlist.id == id)
                }
            },
        )
}
