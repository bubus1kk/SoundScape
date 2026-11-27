package com.soundscape.app.core.domain.model

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaImageRequestTest {
    private val id = UUID(0, 1)

    @Test
    fun `photo rules match all four object types`() {
        for (type in MediaObjectType.entries) {
            val needsPhoto = type == MediaObjectType.PIN || type == MediaObjectType.EVENT
            MediaImageRequest(type, id, photoId = id.takeIf { needsPhoto })
            assertThrows(IllegalArgumentException::class.java) {
                MediaImageRequest(type, id, photoId = id.takeUnless { needsPhoto })
            }
        }
    }

    @Test
    fun `share token is validated and redacted`() {
        val secret = "synthetic-token-".repeat(3)
        val request = MediaImageRequest(MediaObjectType.PLAYLIST, id, shareToken = MediaShareToken(secret))
        assertFalse(request.toString().contains(secret))
        for (token in listOf("short", "x".repeat(257), "x".repeat(32) + "\r\n")) {
            assertThrows(IllegalArgumentException::class.java) { MediaShareToken(token) }
        }
    }
}
