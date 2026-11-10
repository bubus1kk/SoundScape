package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchMusicUseCaseTest {
    private val catalog = FakeCatalog()
    private val useCase = SearchMusicUseCase(catalog)

    @Test fun `query and filters are not rewritten`() = runTest {
        val input = MusicSearch("  music  ", true, listOf(2, 1), listOf(3), PageRequest(50, 7))
        assertEquals(catalog.result, useCase(input))
        assertEquals(input, catalog.input)
    }
    @Test fun `query and pagination boundaries are validated`() = runTest {
        for (query in listOf("", "  ", "a".repeat(201))) assertValidation(useCase(MusicSearch(query)))
        for (page in listOf(PageRequest(0), PageRequest(51), PageRequest(offset = -1))) assertValidation(useCase(MusicSearch("a", page = page)))
        assertEquals(0, catalog.calls)
        assertSuccess(useCase(MusicSearch("a".repeat(200), page = PageRequest(1))))
    }
    @Test fun `filters require positive unique ids at most twenty`() = runTest {
        for (ids in listOf(listOf(0), listOf(-1), listOf(1, 1), (1..21).toList())) {
            assertValidation(useCase(MusicSearch("a", genreIds = ids)))
            assertValidation(useCase(MusicSearch("a", moodIds = ids)))
        }
        assertEquals(0, catalog.calls)
        assertSuccess(useCase(MusicSearch("a", genreIds = (1..20).toList(), moodIds = (1..20).toList())))
    }
    @Test fun `empty results succeed`() = runTest {
        catalog.result = DomainResult.Success(Page(emptyList(), false))
        assertEquals(catalog.result, useCase(MusicSearch("a")))
    }
    @Test fun `network timeout and quota remain distinguishable`() = runTest {
        for (error in failures) { catalog.result = failure(error)
        assertError(useCase(MusicSearch("a")), error) }
    }
    @Test fun `cancellation propagates`() = runTest {
        catalog.before = { cancel() }
        assertCancellation { useCase(MusicSearch("a")) }
    }
}
