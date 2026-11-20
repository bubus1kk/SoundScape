package com.soundscape.app.core.data

import com.soundscape.app.core.network.ProfileMaterialDto
import com.soundscape.app.core.network.ProfileTrackCountDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicProfileAccessTest {
    @Test fun onlyPublicMaterialsOfRequestedOwnerAreMapped() {
        val owner = UUID.randomUUID()
        val id = UUID.randomUUID()
        val visible = ProfileMaterialDto(
            id.toString(), owner.toString(), "Опубликовано", visibility = "PUBLIC",
            playlistTracks = listOf(ProfileTrackCountDto(3)), pinTracks = listOf(ProfileTrackCountDto(2)),
        )
        val rows = listOf(
            visible.copy(visibility = "PRIVATE"),
            visible.copy(visibility = "UNLISTED"),
            visible.copy(ownerId = UUID.randomUUID().toString()),
            visible.copy(id = "invalid"),
            visible,
            visible,
        )
        val playlist = publicMaterials(owner, rows, playlist = true).single()
        assertEquals(id, playlist.id)
        assertEquals(3, playlist.trackCount)
        assertEquals(2, publicMaterials(owner, rows, playlist = false).single().trackCount)
    }
}
