package com.soundscape.app.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.core.domain.model.profileNameError
import com.soundscape.app.core.domain.repository.ProfileRepository
import com.soundscape.app.core.domain.repository.ProfileSession
import com.soundscape.app.core.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProfileLoad<out T> {
    data object Loading : ProfileLoad<Nothing>
    data object SignIn : ProfileLoad<Nothing>
    data object OfflineEmpty : ProfileLoad<Nothing>
    data object Unavailable : ProfileLoad<Nothing>
    data object Error : ProfileLoad<Nothing>
    data class Content<T>(val data: T, val offline: Boolean = false) : ProfileLoad<T>
}

data class ProfileDraft(val name: String, val bio: String, val avatar: String? = null)

data class ProfileEditorState(
    val profile: UserProfile,
    val draft: ProfileDraft,
    val offline: Boolean,
    val saving: Boolean = false,
    val error: DomainError? = null,
    val saved: Boolean = false,
) {
    val hasUnsupportedChanges get() = draft.bio != profile.bio.orEmpty() || draft.avatar != null
    val dirty get() = hasUnsupportedChanges || draft.name != profile.displayName
}

@HiltViewModel
@Suppress("TooManyFunctions")
class ProfileViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val session: ProfileSession,
    private val repository: ProfileRepository,
) : ViewModel() {
    private val publicArgument = savedState.get<String>("profileId")
    private val materialArgument = savedState.get<String>("materialId")
    private val publicId = uuid(publicArgument)
    private val materialId = uuid(materialArgument)
    private val playlist = savedState.get<String>("kind") == "playlist"
    private val revision = MutableStateFlow(0)
    private val mutableOwn = MutableStateFlow<ProfileLoad<UserProfile>>(ProfileLoad.Loading)
    private val mutablePublic = MutableStateFlow<ProfileLoad<PublicProfile>>(ProfileLoad.Loading)
    private val mutableMaterial = MutableStateFlow<ProfileLoad<ProfileMaterial>>(ProfileLoad.Loading)
    private val mutableEditor = MutableStateFlow<ProfileEditorState?>(null)
    private val mutableSignIn = MutableStateFlow<DomainResult<UserProfile>?>(null)
    private val mutableSigningIn = MutableStateFlow(false)
    private var saveJob: Job? = null

    val own = mutableOwn.asStateFlow()
    val public = mutablePublic.asStateFlow()
    val material = mutableMaterial.asStateFlow()
    val editor = mutableEditor.asStateFlow()
    val signInResult = mutableSignIn.asStateFlow()
    val signingIn = mutableSigningIn.asStateFlow()

    init {
        viewModelScope.launch {
            session.restore()
            combine(session.accountId, repository.offline, revision) { id, offline, _ -> id to offline }
                .collectLatest { (id, offline) ->
                    val target = publicId
                    when {
                        publicArgument == null -> loadOwn(id, offline)
                        target == null -> {
                            mutablePublic.value = ProfileLoad.Unavailable
                            mutableMaterial.value = ProfileLoad.Unavailable
                        }
                        materialArgument == null -> loadPublic(target, offline)
                        materialId != null -> loadMaterial(target, materialId)
                        else -> mutableMaterial.value = ProfileLoad.Unavailable
                    }
                }
        }
    }

    fun reload() { revision.update { it + 1 } }

    private suspend fun loadOwn(id: UUID?, offline: Boolean) {
        if (savedState.get<String>("draftAccount") != id?.toString()) clearDraft()
        if (id == null) {
            saveJob?.cancel()
            mutableEditor.value = null
            mutableOwn.value = ProfileLoad.SignIn
            return
        }
        if (mutableEditor.value?.profile?.id != id) {
            saveJob?.cancel()
            mutableEditor.value = null
            mutableOwn.value = ProfileLoad.Loading
        }
        val result = if (offline) offlineState(repository.cachedOwn(id))
        else state(repository.own(id), public = false) { repository.cachedOwn(id) }
        if (session.accountId.value != id) return
        mutableOwn.value = result
        if (result is ProfileLoad.Content) {
            val profile = result.data
            val previous = mutableEditor.value
            val draft = previous?.draft?.takeIf { previous.dirty || previous.saving } ?: restoredDraft(profile)
            mutableEditor.value = ProfileEditorState(
                profile, draft, result.offline,
                saving = previous?.saving == true, error = previous?.error, saved = previous?.saved == true,
            )
        } else if (result is ProfileLoad.SignIn) {
            mutableEditor.value = null
        } else {
            mutableEditor.update { it?.copy(offline = offline) }
        }
    }

    private suspend fun loadPublic(id: UUID, offline: Boolean) {
        if (!offline && mutablePublic.value !is ProfileLoad.Content) mutablePublic.value = ProfileLoad.Loading
        mutablePublic.value = if (offline) offlineState(repository.cachedPublic(id))
        else state(repository.public(id), public = true) { repository.cachedPublic(id) }
    }

    private suspend fun loadMaterial(owner: UUID, id: UUID) {
        if (mutableMaterial.value !is ProfileLoad.Content) mutableMaterial.value = ProfileLoad.Loading
        mutableMaterial.value = state(repository.publicMaterial(owner, id, playlist), public = true) {
            val snapshot = repository.cachedPublic(owner)
            val list = if (playlist) snapshot?.playlists else snapshot?.pins
            list?.firstOrNull { it.id == id }
        }
    }

    private suspend fun <T> state(result: DomainResult<T>, public: Boolean, cache: suspend () -> T?): ProfileLoad<T> =
        when (result) {
            is DomainResult.Success -> ProfileLoad.Content(result.value)
            is DomainResult.Failure -> when (result.error) {
                DomainError.Network -> offlineState(cache())
                DomainError.Unauthenticated -> if (public) ProfileLoad.Unavailable else ProfileLoad.SignIn
                DomainError.Forbidden, DomainError.NotFound ->
                    if (public) ProfileLoad.Unavailable else ProfileLoad.Error
                else -> ProfileLoad.Error
            }
        }

    private fun <T> offlineState(cached: T?): ProfileLoad<T> =
        cached?.let { ProfileLoad.Content(it, offline = true) } ?: ProfileLoad.OfflineEmpty

    fun editName(value: String) = changeDraft { it.copy(name = value) }
    fun editBio(value: String) = changeDraft { it.copy(bio = value) }
    fun editAvatar(value: String?) = changeDraft { it.copy(avatar = value) }

    private fun changeDraft(transform: (ProfileDraft) -> ProfileDraft) {
        val current = mutableEditor.value ?: return
        if (current.saving || session.accountId.value != current.profile.id) return
        val draft = transform(current.draft)
        savedState["draftAccount"] = current.profile.id.toString()
        savedState["draftName"] = draft.name
        savedState["draftBio"] = draft.bio
        savedState["draftAvatar"] = draft.avatar
        mutableEditor.value = current.copy(draft = draft, error = null)
    }

    @Suppress("ReturnCount")
    fun save() {
        val current = mutableEditor.value ?: return
        if (current.saving || current.offline || repository.isOffline()) return
        if (session.accountId.value != current.profile.id) return
        val error = profileNameError(current.draft.name) ?: if (current.hasUnsupportedChanges)
            DomainError.Validation("profile", "unsupported_fields") else null
        if (error != null) {
            mutableEditor.value = current.copy(error = error)
            return
        }
        if (!current.dirty) return
        mutableEditor.value = current.copy(saving = true, error = null)
        saveJob = viewModelScope.launch {
            val result = repository.rename(current.profile.id, current.draft.name.trim())
            if (session.accountId.value != current.profile.id) return@launch
            mutableEditor.update { editor ->
                editor?.let {
                    when (result) {
                        is DomainResult.Success -> {
                            clearDraft()
                            ProfileEditorState(result.value, restoredDraft(result.value), offline = false, saved = true)
                        }
                        is DomainResult.Failure -> it.copy(saving = false, error = result.error)
                    }
                }
            }
        }
    }

    private fun restoredDraft(profile: UserProfile) = ProfileDraft(
        savedState.get<String>("draftName") ?: profile.displayName,
        savedState.get<String>("draftBio") ?: profile.bio.orEmpty(),
        savedState.get<String>("draftAvatar"),
    )

    private fun clearDraft() {
        savedState.remove<String>("draftAccount")
        savedState.remove<String>("draftName")
        savedState.remove<String>("draftBio")
        savedState.remove<String>("draftAvatar")
    }

    fun signOut(onComplete: () -> Unit) {
        mutableOwn.value = ProfileLoad.SignIn
        mutableEditor.value = null
        clearDraft()
        viewModelScope.launch {
            session.signOut()
            onComplete()
        }
    }

    fun signIn(email: String, password: String) {
        if (mutableSigningIn.value) return
        mutableSigningIn.value = true
        mutableSignIn.value = null
        viewModelScope.launch {
            try {
                mutableSignIn.value = SignInUseCase(session)(email.trim(), password)
            } finally {
                mutableSigningIn.value = false
            }
        }
    }

    private fun uuid(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
