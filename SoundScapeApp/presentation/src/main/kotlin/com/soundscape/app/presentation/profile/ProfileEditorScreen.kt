package com.soundscape.app.presentation.profile

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.components.SoundScapeTextField
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.presentation.R

@Composable
fun ProfileEditorScreen(
    load: ProfileLoad<*>,
    state: ProfileEditorState?,
    actions: ProfileEditorActions,
    avatar: @Composable (String) -> Unit = {},
) {
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showAvatar by rememberSaveable { mutableStateOf(false) }
    val back = {
        when {
            state?.saving == true -> Unit
            state?.dirty == true -> showDiscard = true
            else -> actions.back()
        }
    }
    BackHandler(enabled = !showDiscard && !showAvatar, onBack = back)
    Column(Modifier.fillMaxSize().imePadding()) {
        ProfileHeader(R.string.profile_editor, back, compact = true)
        if (state == null) {
            ProfileLoadBody(load, back, actions.retry, actions.signIn)
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(SoundScapeTheme.spacing.space16),
                verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space16),
            ) {
                EditorStatus(state)
                EditorAvatar(state, avatar) { showAvatar = true }
                EditorFields(state, actions)
            }
            EditorFooter(state, actions.save)
        }
    }
    if (showDiscard && state?.dirty == true) {
        ProfileConfirmSheet(
            R.string.profile_unsaved, R.string.profile_unsaved_hint,
            R.string.profile_discard, R.string.profile_continue,
            dismiss = { showDiscard = false },
            confirm = { showDiscard = false; actions.back() },
        )
    }
    if (showAvatar && state != null) {
        EditorAvatarSheet(state.draft.avatar != null, { showAvatar = false }, actions.pickAvatar, actions.removeAvatar)
    }
}

@Composable
private fun EditorStatus(state: ProfileEditorState) {
    val unsupported = (state.error as? DomainError.Validation)?.reason == "unsupported_fields"
    when {
        state.saving -> ProfileStatus(R.string.profile_saving_title, R.string.profile_saving_hint)
        state.offline -> ProfileStatus(R.string.profile_offline, R.string.profile_save_offline)
        unsupported -> ProfileStatus(R.string.profile_save_error, R.string.profile_fields_unavailable, danger = true)
        state.error != null && nameError(state.error) == null ->
            ProfileStatus(R.string.profile_save_error, R.string.profile_save_error_hint, danger = true)
    }
}

@Composable
private fun EditorAvatar(
    state: ProfileEditorState, avatar: @Composable (String) -> Unit, showActions: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space12),
    ) {
        val photo = state.draft.avatar
        ProfileAvatar(
            state.draft.name, Modifier.size(104.dp), image = if (photo == null) null else ({ avatar(photo) }),
        )
        SoundScapeButton(
            stringResource(R.string.profile_change_avatar), showActions, variant = SoundScapeButtonVariant.Outlined,
            modifier = Modifier.widthIn(min = 196.dp).heightIn(min = 44.dp),
            enabled = !state.saving, compact = true, shape = MaterialTheme.shapes.small,
            outlineColor = SoundScapeTheme.colors.outline,
            leadingIcon = { Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(18.dp)) },
        )
        Text(
            stringResource(R.string.profile_avatar_hint), Modifier.widthIn(max = 216.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = SoundScapeTheme.colors.textMuted,
        )
    }
}

@Composable
private fun EditorAvatarSheet(hasPhoto: Boolean, close: () -> Unit, select: () -> Unit, remove: () -> Unit) {
    ProfileSheet(close, profileActions = true) {
        ProfileSheetTitle(stringResource(R.string.profile_avatar_sheet), R.drawable.ic_camera)
        ProfileCaption(
            stringResource(R.string.profile_avatar_sheet_hint),
            Modifier.padding(top = SoundScapeTheme.spacing.space12),
        )
        Column {
            AvatarSheetAction(stringResource(R.string.profile_select_photo), R.drawable.ic_image_plus) {
                close()
                select()
            }
            HorizontalDivider(color = SoundScapeTheme.colors.surfaceAlt)
            AvatarSheetAction(
                stringResource(R.string.profile_remove_photo), R.drawable.ic_trash_2, enabled = hasPhoto, danger = true,
            ) {
                close()
                remove()
            }
        }
        SoundScapeButton(
            stringResource(R.string.profile_cancel), close, Modifier.fillMaxWidth(), SoundScapeButtonVariant.Outlined,
            compact = true, shape = MaterialTheme.shapes.small, outlineColor = SoundScapeTheme.colors.outline,
        )
    }
}

@Composable
private fun AvatarSheetAction(
    label: String, @DrawableRes icon: Int, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) {
        val tint = when {
            !enabled -> colors.textMuted
            danger -> colors.danger
            else -> colors.primary
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
            Text(
                label, style = MaterialTheme.typography.labelLarge,
                color = if (danger || !enabled) tint else colors.text,
            )
        }
    }
}

@Composable
private fun EditorFields(state: ProfileEditorState, actions: ProfileEditorActions) {
    val error = nameError(state.error)
    SoundScapeTextField(
        state.draft.name, actions.name, stringResource(R.string.profile_name), Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.profile_name_placeholder),
        isError = error != null, errorText = error?.let { stringResource(it) }, enabled = !state.saving,
        externalLabelFieldHeight = 48.dp,
    )
    SoundScapeTextField(
        state.draft.bio, actions.bio, stringResource(R.string.profile_bio), Modifier.fillMaxWidth(),
        supportingText = stringResource(R.string.profile_bio_hint), enabled = !state.saving,
        externalLabelFieldHeight = 112.dp, labelHint = stringResource(R.string.profile_optional),
    )
    Surface(shape = MaterialTheme.shapes.medium, color = SoundScapeTheme.colors.surface) {
        val spacing = SoundScapeTheme.spacing
        Column(
            Modifier.fillMaxWidth().padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space8),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_shield_check), null, Modifier.size(18.dp),
                    tint = SoundScapeTheme.colors.primary,
                )
                Text(stringResource(R.string.profile_account), style = MaterialTheme.typography.labelLarge)
            }
            ProfileCaption(stringResource(R.string.profile_account_hint))
        }
    }
}

@Composable
private fun EditorFooter(state: ProfileEditorState, save: () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    Surface(color = SoundScapeTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = spacing.space16, vertical = spacing.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space4),
        ) {
            SoundScapeButton(
                stringResource(R.string.profile_save), save, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !state.offline && !state.saving, loading = state.saving,
                loadingText = stringResource(R.string.profile_saving),
                compact = true, shape = MaterialTheme.shapes.small,
            )
            val hint = when {
                state.offline -> R.string.profile_save_offline
                state.hasUnsupportedChanges -> R.string.profile_fields_unavailable
                else -> R.string.profile_save_hint
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                color = SoundScapeTheme.colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}

private fun nameError(error: DomainError?): Int? = when {
    error == DomainError.AlreadyExists -> R.string.profile_name_taken
    error is DomainError.Validation && error.reason == "required" -> R.string.profile_name_required
    error is DomainError.Validation && error.reason == "max_40" -> R.string.profile_name_too_long
    else -> null
}

@Composable
fun ProfileSignInScreen(back: () -> Unit, signIn: (String, String) -> Unit, loading: Boolean, error: DomainError?) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val spacing = SoundScapeTheme.spacing
    Column(Modifier.fillMaxSize().imePadding()) {
        ProfileHeader(R.string.profile_sign_in, back)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            SoundScapeTextField(
                email, { email = it }, stringResource(R.string.profile_email), Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !loading,
            )
            SoundScapeTextField(
                password, { password = it }, stringResource(R.string.profile_password), Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), enabled = !loading,
            )
            if (error != null) {
                ProfileStatus(
                    if (error == DomainError.InvalidCredentials || error is DomainError.Validation)
                        R.string.profile_sign_in_invalid else R.string.profile_sign_in_failed,
                    danger = true,
                )
            }
            SoundScapeButton(stringResource(R.string.profile_sign_in), { signIn(email, password) },
                Modifier.fillMaxWidth(), loading = loading)
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun ProfileEditorPreview() {
    val draft = ProfileDraft(PreviewProfile.displayName, PreviewProfile.bio.orEmpty())
    SoundScapeTheme {
        ProfileEditorScreen(
            ProfileLoad.Content(PreviewProfile), ProfileEditorState(PreviewProfile, draft, offline = false),
            ProfileEditorActions({}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}
