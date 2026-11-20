package com.soundscape.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.model.profileNameError
import com.soundscape.app.core.domain.repository.ProfileRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = UserProfile(UUID.randomUUID(), "Алиса")
    private val maria = UserProfile(UUID.randomUUID(), "Мария")
    private val session = Session(alice)
    private val repository = Repository(alice, maria)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    @Test fun validatesBlankAndUnicodeNameLength() {
        assertEquals("required", profileNameError("  \n")?.reason)
        assertEquals("max_40", profileNameError("a".repeat(41))?.reason)
        assertNull(profileNameError(" 🎵" + "а".repeat(39) + " "))
    }

    @Test fun savePreventsDuplicateRequestsThenKeepsDraftOnFailure() = runTest(dispatcher) {
        val model = ProfileViewModel(SavedStateHandle(), session, repository)
        runCurrent()
        model.editName("  Новое имя ")
        val pending = CompletableDeferred<DomainResult<UserProfile>>()
        repository.pending = pending
        model.save()
        model.save()
        runCurrent()
        assertEquals(listOf("Новое имя"), repository.writes)
        assertTrue(model.editor.value!!.saving)
        pending.complete(DomainResult.Failure(DomainError.Server))
        runCurrent()
        assertEquals("  Новое имя ", model.editor.value!!.draft.name)
        assertEquals(DomainError.Server, model.editor.value!!.error)
        repository.pending = null
        model.save()
        runCurrent()
        assertTrue(model.editor.value!!.saved)
        assertEquals("Новое имя", model.editor.value!!.profile.displayName)
    }

    @Test fun unsupportedFieldsAndOfflineDoNotWriteAnything() = runTest(dispatcher) {
        val model = ProfileViewModel(SavedStateHandle(), session, repository)
        runCurrent()
        model.editBio("Черновик")
        model.save()
        assertEquals(DomainError.Validation("profile", "unsupported_fields"), model.editor.value!!.error)
        assertEquals("Черновик", model.editor.value!!.draft.bio)
        repository.offline.value = true
        runCurrent()
        model.save()
        assertTrue(model.editor.value!!.offline)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun restoredDraftAndOfflineCacheAreScopedToTheirAccount() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("draftAccount" to alice.id.toString(), "draftName" to "Черновик Алисы"))
        repository.offline.value = true
        val model = ProfileViewModel(handle, session, repository)
        runCurrent()
        assertEquals("Черновик Алисы", model.editor.value!!.draft.name)
        session.accountId.value = maria.id
        runCurrent()
        assertEquals(ProfileLoad.OfflineEmpty, model.own.value)
        assertNull(model.editor.value)
        assertNull(handle.get<String>("draftName"))
        session.accountId.value = null
        runCurrent()
        assertEquals(ProfileLoad.SignIn, model.own.value)
    }

    @Test fun guestUsesPublicProfileAndHandlesUnavailableResponse() = runTest(dispatcher) {
        session.accountId.value = null
        val model = ProfileViewModel(SavedStateHandle(mapOf("profileId" to maria.id.toString())), session, repository)
        runCurrent()
        assertEquals(ProfileLoad.Content(PublicProfile(maria, emptyList(), emptyList())), model.public.value)
        repository.publicError = DomainError.Forbidden
        model.reload()
        runCurrent()
        assertEquals(ProfileLoad.Unavailable, model.public.value)
    }

    @Test fun invalidPublicRouteDoesNotLoadPersonalProfile() = runTest(dispatcher) {
        val model = ProfileViewModel(SavedStateHandle(mapOf("profileId" to "invalid")), session, repository)
        runCurrent()
        assertEquals(ProfileLoad.Unavailable, model.public.value)
        assertEquals(ProfileLoad.Loading, model.own.value)
    }

    private class Session(user: UserProfile) : ProfileSession {
        override val accountId = MutableStateFlow<UUID?>(user.id)
        override suspend fun restore() = Unit
        override suspend fun signOut() { accountId.value = null }
        override suspend fun currentUser() = DomainResult.Success(null)
        override suspend fun signIn(email: String, password: String) =
            DomainResult.Failure(DomainError.InvalidCredentials)
    }

    private class Repository(private val own: UserProfile, private val public: UserProfile) : ProfileRepository {
        override val offline = MutableStateFlow(false)
        val writes = mutableListOf<String>()
        var pending: CompletableDeferred<DomainResult<UserProfile>>? = null
        var publicError: DomainError? = null
        override fun isOffline() = offline.value
        override suspend fun own(id: UUID) = DomainResult.Success(own)
        override suspend fun public(id: UUID): DomainResult<PublicProfile> =
            publicError?.let { DomainResult.Failure(it) }
                ?: DomainResult.Success(PublicProfile(public, emptyList(), emptyList()))
        override suspend fun cachedOwn(id: UUID) = own.takeIf { it.id == id }
        override suspend fun cachedPublic(id: UUID) = null
        override suspend fun publicMaterial(owner: UUID, id: UUID, playlist: Boolean): DomainResult<ProfileMaterial> =
            DomainResult.Failure(DomainError.NotFound)
        override suspend fun rename(id: UUID, name: String): DomainResult<UserProfile> {
            writes += name
            return pending?.await() ?: DomainResult.Success(own.copy(displayName = name))
        }
    }
}
