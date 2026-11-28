package com.soundscape.app.core.domain.usecase

import com.soundscape.app.core.domain.model.*
import com.soundscape.app.core.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccountAndSyncTest {
    @Test fun `account lifecycle keeps guest email recovery and deletion outcomes distinct`() = runTest {
        val auth = FakeAuth()
        val calls = mutableListOf<String>()
        val repository = object : AccountRepository, AuthRepository by auth {
            override val session = MutableStateFlow<AccountSession>(AccountSession.Guest)
            override suspend fun register(email: String, password: String): DomainResult<RegistrationStatus> {
                calls.add(email)
                return DomainResult.Success(RegistrationStatus.CONFIRMATION_REQUIRED)
            }
            override suspend fun verifyEmail(purpose: EmailVerificationPurpose, proof: EmailVerificationProof): DomainResult<AccountSession> {
                calls.add(purpose.name)
                return DomainResult.Success(AccountSession.Active(id))
            }
            override suspend fun recover(email: String): DomainResult<Unit> {
                calls.add(email)
                return DomainResult.Success(Unit)
            }
            override suspend fun changePassword(password: String): DomainResult<Unit> {
                calls.add("changePassword")
                return DomainResult.Success(Unit)
            }
            override suspend fun refresh() = DomainResult.Failure(DomainError.SessionUnavailable(SessionFailure.REVOKED))
            override suspend fun logout() = DomainResult.Success(Unit)
            override suspend fun deleteAccount(): DomainResult<AccountDeletionStatus> {
                calls.add("delete")
                return DomainResult.Success(AccountDeletionStatus.PENDING)
            }
        }
        val account = ManageAccountUseCase(repository)
        assertValidation(account.register("bad", "secret"))
        assertValidation(account.register("owner@example.test", ""))
        assertValidation(account.recover("bad"))
        assertValidation(account.verify(EmailVerificationPurpose.SIGN_UP, EmailVerificationProof.LinkHash(" ")))
        assertValidation(account.changePassword(""))
        assertValidation(account.delete(false))
        assertTrue(calls.isEmpty())
        assertEquals(DomainResult.Success(RegistrationStatus.CONFIRMATION_REQUIRED),
            account.register("owner@example.test", "secret"))
        assertSuccess(account.recover("owner@example.test"))
        assertEquals(DomainResult.Success(AccountSession.Active(id)), account.verify(EmailVerificationPurpose.RECOVERY, EmailVerificationProof.Code("owner@example.test", "otp")))
        assertSuccess(account.changePassword("new secret"))
        assertEquals(DomainResult.Success(AccountDeletionStatus.PENDING), account.delete(true))
        assertEquals(listOf("owner@example.test", "owner@example.test", "RECOVERY", "changePassword", "delete"), calls)
    }

    @Test fun `profile editing validates name and preserves avatar selection`() = runTest {
        val edits = mutableListOf<ProfileEdit>()
        val useCase = EditProfileUseCase(object : ProfileEditor {
            override suspend fun update(edit: ProfileEdit): DomainResult<UserProfile> {
                edits.add(edit)
                return DomainResult.Success(profile)
            }
            override suspend fun uploadAvatar(image: MediaReference) = DomainResult.Success(image.value)
        })
        assertValidation(useCase(ProfileEdit("  ")))
        assertValidation(useCase(ProfileEdit("a".repeat(41))))
        assertTrue(edits.isEmpty())
        assertEquals(DomainResult.Success(profile), useCase(ProfileEdit("  Name  ", AvatarChange.Replace("avatar.png"))))
        assertEquals(listOf(ProfileEdit("Name", AvatarChange.Replace("avatar.png"))), edits)
    }

    @Test fun `sync scopes claims and persists failure before returning to its caller`() = runTest {
        val edit = OfflineEdit.Playlist(PlaylistInput("Local update", Visibility.PRIVATE, listOf(id)))
        val operation = SyncOperation(MutationIdentity(profile.id, id, otherId, 2), edit)
        val claim = SyncClaim(UUID.randomUUID(), (operation.start() as DomainResult.Success).value)
        val auth = FakeAuth()
        val calls = mutableListOf<Any>()
        var next: DomainResult<SyncClaim?> = DomainResult.Success(claim)
        var finish: DomainResult<Unit> = DomainResult.Success(Unit)
        var applied: DomainResult<Unit> = DomainResult.Failure(DomainError.Network)
        val outbox = object : SyncOutbox {
            override fun observe(accountId: UUID) = MutableStateFlow(listOf(operation))
            override suspend fun enqueue(operation: SyncOperation) = DomainResult.Success(operation)
            override suspend fun claim(accountId: UUID): DomainResult<SyncClaim?> {
                calls.add(accountId)
                return next
            }
            override suspend fun finish(claimed: SyncClaim, result: DomainResult<Unit>): DomainResult<Unit> {
                calls.add(claimed)
                calls.add(result)
                return finish
            }
            override suspend fun resolve(conflicted: SyncOperation, choice: ConflictChoice) = DomainResult.Success(Unit)
        }
        val sync = SyncNextOperationUseCase(auth, outbox) {
            calls.add(it)
            applied
        }
        auth.current = DomainResult.Success(null)
        assertError(sync(), DomainError.SessionUnavailable(SessionFailure.MISSING))
        assertTrue(calls.isEmpty())
        auth.current = DomainResult.Success(profile)
        assertError(sync(), DomainError.Network)
        assertEquals(listOf(profile.id, claim.operation, claim, applied), calls)
        applied = DomainResult.Success(Unit)
        assertEquals(DomainResult.Success(true), sync())
        finish = DomainResult.Failure(DomainError.Unexpected)
        assertError(sync(), DomainError.Unexpected)
        next = DomainResult.Success(null)
        assertEquals(DomainResult.Success(false), sync())
        next = DomainResult.Failure(DomainError.Forbidden)
        assertError(sync(), DomainError.Forbidden)
    }
}
