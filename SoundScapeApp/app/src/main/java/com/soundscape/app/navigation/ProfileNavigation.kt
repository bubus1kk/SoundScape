package com.soundscape.app.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.profile.PersonalProfileActions
import com.soundscape.app.presentation.profile.PersonalProfileScreen
import com.soundscape.app.presentation.profile.ProfileEditorActions
import com.soundscape.app.presentation.profile.ProfileEditorScreen
import com.soundscape.app.presentation.profile.ProfileMaterialScreen
import com.soundscape.app.presentation.profile.ProfileSection
import com.soundscape.app.presentation.profile.ProfileSignInScreen
import com.soundscape.app.presentation.profile.ProfileViewModel
import com.soundscape.app.presentation.profile.PublicProfileScreen
import java.util.UUID

internal object ProfileNavigation {
    const val Own = "profile"
    const val Edit = "profile/edit"
    const val Public = "profile/public/{profileId}"
    const val Material = "profile/public/{profileId}/{kind}/{materialId}"
    const val SignIn = "profile/sign-in"
    const val Section = "profile/section/{section}"

    fun public(id: UUID) = "profile/public/$id"

    fun material(owner: UUID, id: UUID, playlist: Boolean): String {
        val kind = if (playlist) "playlist" else "pin"
        return "profile/public/$owner/$kind/$id"
    }

    fun section(section: String) = "profile/section/$section"
}

internal fun NavGraphBuilder.installProfileRoutes(
    controller: NavHostController,
    imageLoader: ImageLoader?,
    personalContent: @Composable ((() -> Unit) -> Unit)?,
) {
    val back: () -> Unit = { controller.popBackStack() }
    val signIn: () -> Unit = { controller.openProfileRoute(ProfileNavigation.SignIn) }
    composable(ProfileNavigation.Own) { entry ->
        if (personalContent != null) {
            personalContent(back)
        } else {
            val model: ProfileViewModel = hiltViewModel()
            val state by model.own.collectAsStateWithLifecycle()
            val updated by entry.savedStateHandle.getStateFlow("profileUpdated", false).collectAsStateWithLifecycle()
            LaunchedEffect(updated) {
                if (updated) {
                    entry.savedStateHandle["profileUpdated"] = false
                    model.reload()
                }
            }
            PersonalProfileScreen(state, PersonalProfileActions(
                back = back,
                settings = { controller.openProfileRoute(ProfileNavigation.section("settings")) },
                edit = { controller.openProfileRoute(ProfileNavigation.Edit) },
                publicView = { controller.openProfileRoute(ProfileNavigation.public(it)) },
                openSection = { controller.openProfileSection(it) },
                logout = {
                    model.signOut { if (controller.currentDestination?.route == ProfileNavigation.Own) signIn() }
                },
                signIn = signIn,
                retry = model::reload,
            ))
        }
    }
    composable(ProfileNavigation.Public) {
        val model: ProfileViewModel = hiltViewModel()
        val state by model.public.collectAsStateWithLifecycle()
        PublicProfileScreen(state, back, model::reload) { owner, id, playlist ->
            controller.openProfileRoute(ProfileNavigation.material(owner, id, playlist))
        }
    }
    composable(ProfileNavigation.Material) { entry ->
        val model: ProfileViewModel = hiltViewModel()
        val state by model.material.collectAsStateWithLifecycle()
        ProfileMaterialScreen(state, entry.arguments?.getString("kind") == "playlist", back, model::reload)
    }
    composable(ProfileNavigation.Edit) {
        EditProfileRoute(imageLoader, back, signIn) {
            controller.previousBackStackEntry?.savedStateHandle?.set("profileUpdated", true)
            back()
        }
    }
    composable(ProfileNavigation.SignIn) { ProfileSignInRoute(back) }
    composable(ProfileNavigation.Section) { entry ->
        val name = entry.arguments?.getString("section")
        val section = ProfileSection.entries.firstOrNull { it.name == name }
        PendingScreen(stringResource(section?.title ?: R.string.profile_settings), back)
    }
}

private fun NavHostController.openProfileSection(section: ProfileSection) =
    openProfileRoute(
        if (section == ProfileSection.Library) AppDestination.Library.route
        else ProfileNavigation.section(section.name),
    )

internal fun NavHostController.openProfileRoute(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
private fun EditProfileRoute(imageLoader: ImageLoader?, back: () -> Unit, signIn: () -> Unit, onSaved: () -> Unit) {
    val model: ProfileViewModel = hiltViewModel()
    val load by model.own.collectAsStateWithLifecycle()
    val state by model.editor.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.editAvatar(uri.toString())
    }
    LaunchedEffect(state?.saved) {
        if (state?.saved == true) onSaved()
    }
    ProfileEditorScreen(
        load, state, ProfileEditorActions(
            back = back, retry = model::reload, signIn = signIn, name = model::editName, bio = model::editBio,
            pickAvatar = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            removeAvatar = { model.editAvatar(null) }, save = model::save,
        ),
    ) { uri ->
        if (imageLoader != null) {
            AsyncImage(
                model = Uri.parse(uri), imageLoader = imageLoader, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ProfileSignInRoute(back: () -> Unit) {
    val model: ProfileViewModel = hiltViewModel()
    val result by model.signInResult.collectAsStateWithLifecycle()
    val loading by model.signingIn.collectAsStateWithLifecycle()
    LaunchedEffect(result) {
        if (result is DomainResult.Success) back()
    }
    ProfileSignInScreen(back, model::signIn, loading, (result as? DomainResult.Failure)?.error)
}
