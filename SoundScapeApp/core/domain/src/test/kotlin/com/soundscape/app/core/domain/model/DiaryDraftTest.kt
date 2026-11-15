package com.soundscape.app.core.domain.model

import com.soundscape.app.core.domain.usecase.ValidateDiaryDraftReadinessUseCase
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DiaryDraftTest {
    @Test fun emptyDraftIsPersistableButNotReady() {
        assertEquals(DomainResult.Success(Unit), DiaryDraftInput().validate())
        assertEquals(listOf("title", "body", "eventDate", "location", "trackIds"),
            ValidateDiaryDraftReadinessUseCase()(DiaryDraftInput()))
    }
    @Test fun privateVisibilityIsDefault() { assertEquals(Visibility.PRIVATE, DiaryDraftInput().visibility) }
    @Test fun tenPhotosAcceptedElevenRejected() {
        val photos = List(11) { DiaryDraftPhoto(UUID.randomUUID()) }
        assertEquals(DomainResult.Success(Unit), DiaryDraftInput(photos = photos.take(10)).validate())
        assertTrue(DiaryDraftInput(photos = photos).validate() is DomainResult.Failure)
    }
    @Test fun duplicatesAndOversizedTitleRejected() {
        val id = UUID.randomUUID()
        assertTrue(DiaryDraftInput(trackIds = listOf(id, id)).validate() is DomainResult.Failure)
        assertTrue(DiaryDraftInput(title = "x".repeat(151)).validate() is DomainResult.Failure)
        val photo = DiaryDraftPhoto(id)
        assertTrue(DiaryDraftInput(photos = listOf(photo, photo)).validate() is DomainResult.Failure)
    }
    @Test fun completeFormCanBeReadyWithoutPin() {
        val input = DiaryDraftInput("Поездка", LocalDate.of(2026, 1, 1), "Казань", trackIds =
            listOf(UUID.randomUUID()), body = "Описание события")
        assertTrue(ValidateDiaryDraftReadinessUseCase()(input).isEmpty())
    }
    @Test fun partialFormHasNoInventedDateOrTracks() {
        val input = DiaryDraftInput(body = "Только текст")
        assertNull(input.eventDate)
        assertTrue(input.trackIds.isEmpty())
        assertEquals(DomainResult.Success(Unit), input.validate())
    }
    @Test fun readyEntryRequiresDescriptionButDraftDoesNot() {
        for (body in listOf("", "   ")) {
            val input = DiaryDraftInput("Поездка", LocalDate.of(2026, 1, 1), "Казань",
                trackIds = listOf(UUID.randomUUID()), body = body)
            assertEquals(listOf("body"), ValidateDiaryDraftReadinessUseCase()(input))
            assertEquals(DomainResult.Success(Unit), input.validate())
        }
    }

}
