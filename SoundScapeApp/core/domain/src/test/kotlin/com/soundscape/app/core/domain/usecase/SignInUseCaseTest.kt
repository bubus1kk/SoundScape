package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SignInUseCaseTest {
    private val auth = FakeAuth()
    private val useCase = SignInUseCase(auth)

    @Test fun `original credentials are passed unchanged`() = runTest {
        assertEquals(DomainResult.Success(profile), useCase("user@example.com", " pass "))
        assertEquals("user@example.com" to " pass ", auth.credentials)
    }
    @Test fun `simple password is accepted without registration rules`() = runTest {
        assertSuccess(useCase("user@example.com", "x"))
        assertSuccess(useCase("user@example.com", " "))
    }
    @Test fun `invalid input prevents sign in`() = runTest {
        for (email in listOf("", "a", "a@@b.com", "@b.com", "a@", "a@.com", "a@b.", " a@b.com")) assertValidation(useCase(email, "p"))
        assertValidation(useCase("a@b.com", ""))
        assertEquals(0, auth.calls)
    }
    @Test fun `incorrect password returns invalid credentials`() = runTest {
        auth.signedIn = failure(DomainError.InvalidCredentials)
        assertError(useCase("a@b.com", "bad"), DomainError.InvalidCredentials)
        assertEquals(1, auth.calls)
    }
    @Test fun `transport errors are not rewritten`() = runTest {
        for (error in failures) { auth.signedIn = failure(error)
        assertError(useCase("a@b.com", "p"), error) }
    }
    @Test fun `cancellation propagates`() = runTest {
        auth.before = { cancel() }
        assertCancellation { useCase("a@b.com", "p") }
    }
}
