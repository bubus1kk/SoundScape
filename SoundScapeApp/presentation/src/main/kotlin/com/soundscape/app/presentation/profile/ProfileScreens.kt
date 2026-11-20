@file:Suppress("TooManyFunctions", "MagicNumber")

package com.soundscape.app.presentation.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.ProfileMaterial
import com.soundscape.app.core.domain.model.PublicProfile
import com.soundscape.app.core.domain.model.UserProfile
import com.soundscape.app.presentation.R
import java.util.UUID

enum class ProfileSection(@get:StringRes val title: Int, @get:StringRes val hint: Int, @get:DrawableRes val icon: Int) {
    Library(R.string.nav_library, R.string.profile_library_hint, R.drawable.ic_library),
    Diary(R.string.profile_diary, R.string.profile_diary_hint, R.drawable.ic_book_open),
    Journeys(R.string.profile_journeys, R.string.profile_journeys_hint, R.drawable.ic_route),
    History(R.string.profile_history, R.string.profile_history_hint, R.drawable.ic_history),
}

data class PersonalProfileActions(
    val back: () -> Unit,
    val settings: () -> Unit,
    val edit: () -> Unit,
    val publicView: (UUID) -> Unit,
    val openSection: (ProfileSection) -> Unit,
    val logout: () -> Unit,
    val signIn: () -> Unit,
    val retry: () -> Unit,
)

data class ProfileEditorActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    val signIn: () -> Unit,
    val name: (String) -> Unit,
    val bio: (String) -> Unit,
    val pickAvatar: () -> Unit,
    val removeAvatar: () -> Unit,
    val save: () -> Unit,
)

@Composable
fun PersonalProfileScreen(state: ProfileLoad<UserProfile>, actions: PersonalProfileActions) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ProfileHeader(
            R.string.profile_mine, actions.back, R.string.profile_settings, R.drawable.ic_settings, actions.settings,
            compact = true,
        )
        if (state is ProfileLoad.Content) {
            PersonalContent(state.data, state.offline, actions) { confirmLogout = true }
        } else {
            ProfileLoadBody(state, actions.back, actions.retry, actions.signIn)
        }
    }
    if (confirmLogout && state is ProfileLoad.Content) {
        ProfileConfirmSheet(
            R.string.profile_confirm_logout, R.string.profile_logout_hint,
            R.string.profile_exit, R.string.profile_cancel,
            dismiss = { confirmLogout = false },
            confirm = { confirmLogout = false; actions.logout() },
        )
    }
}

@Composable
private fun PersonalContent(
    profile: UserProfile, offline: Boolean, actions: PersonalProfileActions, requestLogout: () -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    Column(
        Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 560.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (offline) ProfileStatus(R.string.profile_offline, R.string.profile_offline_own)
        PersonalProfileCard(profile, actions)
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.profile_sections), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                stringResource(R.string.profile_personal_access), Modifier.weight(1f),
                textAlign = TextAlign.End, color = SoundScapeTheme.colors.textMuted,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            )
        }
        Surface(shape = MaterialTheme.shapes.medium, color = SoundScapeTheme.colors.surface) {
            Column {
                ProfileSection.entries.forEachIndexed { index, section ->
                    if (index != 0) {
                        HorizontalDivider(
                            Modifier.padding(start = 66.dp, end = spacing.space16),
                            color = SoundScapeTheme.colors.outline.copy(alpha = 0.55f),
                        )
                    }
                    PersonalSectionRow(section) {
                        actions.openSection(section)
                    }
                }
            }
        }
        PersonalLogoutRow(requestLogout)
    }
}

@Composable
private fun PersonalProfileCard(profile: UserProfile, actions: PersonalProfileActions) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    val bio = profile.bio?.takeUnless(String::isBlank)
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = spacing.space16, end = 14.dp, bottom = spacing.space8),
            verticalArrangement = Arrangement.spacedBy(spacing.space24),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.space12),
                verticalAlignment = if (bio == null) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(
                    Modifier.size(78.dp).background(colors.primarySoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_user), null, Modifier.size(32.dp), tint = colors.primary)
                }
                Column(
                    Modifier.weight(1f).padding(top = if (bio == null) spacing.space0 else spacing.space4),
                    verticalArrangement = Arrangement.spacedBy(spacing.space12),
                ) {
                    Text(
                        profile.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = colors.text,
                    )
                    if (bio != null) {
                        Text(
                            bio, Modifier.widthIn(max = 200.dp), color = colors.textMuted,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        )
                    }
                }
            }
            PersonalProfileButtons(actions.edit) { actions.publicView(profile.id) }
        }
    }
}

@Composable
private fun PersonalProfileButtons(edit: () -> Unit, publicView: () -> Unit) {
    val gap = SoundScapeTheme.spacing.space8
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val buttons: @Composable (Modifier) -> Unit = { modifier ->
            SoundScapeButton(
                stringResource(R.string.profile_edit), edit, modifier.heightIn(min = 42.dp),
                compact = true, shape = MaterialTheme.shapes.small,
            )
            SoundScapeButton(
                stringResource(R.string.profile_public_view), publicView, modifier.heightIn(min = 42.dp),
                variant = SoundScapeButtonVariant.Outlined, compact = true,
                shape = MaterialTheme.shapes.small, outlineColor = SoundScapeTheme.colors.outline,
            )
        }
        if (maxWidth < 260.dp * LocalDensity.current.fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) { buttons(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) { buttons(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PersonalSectionRow(section: ProfileSection, onClick: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.space12, vertical = spacing.space8),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(spacing.space40).background(colors.surfaceAlt, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(section.icon), null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(section.title), style = MaterialTheme.typography.labelLarge, color = colors.text)
            Text(
                stringResource(section.hint), color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

@Composable
private fun PersonalLogoutRow(onClick: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = spacing.space16, vertical = spacing.space8),
            horizontalArrangement = Arrangement.spacedBy(spacing.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_log_out), null, Modifier.size(20.dp), tint = colors.danger)
            Text(
                stringResource(R.string.profile_logout), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = colors.danger,
            )
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = colors.textMuted)
        }
    }
}

@Composable
fun PublicProfileScreen(
    state: ProfileLoad<PublicProfile>,
    back: () -> Unit,
    retry: () -> Unit,
    openMaterial: (UUID, UUID, Boolean) -> Unit,
) {
    var showShare by rememberSaveable { mutableStateOf(false) }
    var playlists by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        val share = if (state is ProfileLoad.Content) ({ showShare = true }) else null
        ProfileHeader(R.string.profile_public, back, R.string.profile_share, R.drawable.ic_share, share)
        if (state is ProfileLoad.Content) {
            PublicContent(state.data, state.offline, playlists, { playlists = it }, { showShare = true }, openMaterial)
        } else {
            ProfileLoadBody(state, back, retry)
        }
    }
    if (showShare && state is ProfileLoad.Content) ProfileShareSheet { showShare = false }
}

@Composable
private fun PublicContent(
    profile: PublicProfile,
    offline: Boolean,
    playlists: Boolean,
    setTab: (Boolean) -> Unit,
    share: () -> Unit,
    open: (UUID, UUID, Boolean) -> Unit,
) {
    val spacing = SoundScapeTheme.spacing
    val selected = if (playlists) profile.playlists else profile.pins
    val icon = if (playlists) R.drawable.ic_music else R.drawable.ic_map_pin
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space12),
    ) {
        if (offline) item { ProfileStatus(R.string.profile_offline, R.string.profile_offline_public) }
        item {
            ProfileCard(profile.user) {
                ProfileCaption(stringResource(R.string.profile_public_label))
                SoundScapeButton(stringResource(R.string.profile_share), share, Modifier.fillMaxWidth(), compact = true)
            }
        }
        item { ProfileTabs(playlists, setTab) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val title = if (playlists) R.string.profile_public_playlists else R.string.profile_public_pins
                Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ProfileCaption(selected.size.toString())
            }
        }
        if (selected.isEmpty()) item {
            ProfileMessage(R.string.profile_nothing_published, null, icon)
        }
        items(selected, key = { it.id.toString() }) { material ->
            Surface(shape = MaterialTheme.shapes.large, color = SoundScapeTheme.colors.surface) {
                ProfileRow(material.title, trackCount(material.trackCount), icon) {
                    open(profile.user.id, material.id, playlists)
                }
            }
        }
        if (selected.isNotEmpty()) item { ProfileCaption(stringResource(R.string.profile_privacy_hint)) }
    }
}

@Composable
private fun ProfileTabs(playlists: Boolean, select: (Boolean) -> Unit) {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Row(Modifier.fillMaxWidth().padding(spacing.space4).selectableGroup()) {
            for (tab in listOf(true, false)) {
                val selected = tab == playlists
                Box(
                    Modifier.weight(1f).heightIn(min = spacing.space48)
                        .background(if (selected) colors.primarySoft else colors.surface, MaterialTheme.shapes.small)
                        .selectable(selected, role = Role.Tab) { select(tab) }.padding(spacing.space8),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (tab) R.string.profile_playlists else R.string.profile_pins),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.onPrimarySoft else colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileShareSheet(close: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    ProfileSheet(close, profileActions = true) {
        ProfileSheetTitle(stringResource(R.string.profile_share), R.drawable.ic_share)
        Text(
            stringResource(R.string.profile_share_hint), Modifier.padding(top = spacing.space16),
            color = colors.textMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceAlt) {
            Row(
                Modifier.fillMaxWidth().padding(spacing.space16),
                horizontalArrangement = Arrangement.spacedBy(spacing.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_qr_code), null, Modifier.size(28.dp), tint = colors.primary)
                Text(
                    stringResource(R.string.profile_share_no_url), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = colors.textMuted,
                )
            }
        }
        SoundScapeButton(
            stringResource(R.string.profile_copy_link), {}, Modifier.fillMaxWidth().heightIn(min = 42.dp),
            enabled = false, compact = true, shape = MaterialTheme.shapes.small,
        )
        SoundScapeButton(
            stringResource(R.string.profile_close), close, Modifier.fillMaxWidth().heightIn(min = 42.dp),
            SoundScapeButtonVariant.Outlined, compact = true,
            shape = MaterialTheme.shapes.small, outlineColor = colors.outline,
        )
    }
}

@Composable
fun ProfileMaterialScreen(state: ProfileLoad<ProfileMaterial>, playlist: Boolean, back: () -> Unit, retry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ProfileHeader(if (playlist) R.string.profile_playlist else R.string.profile_pin, back)
        if (state is ProfileLoad.Content) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SoundScapeTheme.spacing.space16),
            ) {
                if (state.offline) ProfileStatus(R.string.profile_offline, R.string.profile_offline_public)
                Text(state.data.title, style = MaterialTheme.typography.headlineSmall)
                ProfileCaption(trackCount(state.data.trackCount))
                state.data.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        } else if (state == ProfileLoad.Unavailable || state == ProfileLoad.Error) {
            val unavailable = state == ProfileLoad.Unavailable
            Box(Modifier.fillMaxSize().padding(SoundScapeTheme.spacing.space24), contentAlignment = Alignment.Center) {
                ProfileMessage(
                    if (unavailable) R.string.profile_material_unavailable else R.string.profile_material_error,
                    null, R.drawable.ic_alert, if (unavailable) R.string.profile_return else R.string.profile_retry,
                    if (unavailable) back else retry,
                )
            }
        } else ProfileLoadBody(state, back, retry)
    }
}

@Composable
private fun trackCount(count: Int) = pluralStringResource(R.plurals.profile_tracks, count, count)

@Composable
internal fun ProfileHeader(
    @StringRes title: Int,
    back: () -> Unit,
    @StringRes actionLabel: Int? = null,
    @DrawableRes actionIcon: Int? = null,
    action: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val colors = SoundScapeTheme.colors
    Surface(color = colors.surface, contentColor = colors.text) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (compact) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backLabel = stringResource(R.string.profile_back)
            IconButton(back, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.rotate(180f))
            }
            Text(
                stringResource(title), Modifier.weight(1f).padding(vertical = SoundScapeTheme.spacing.space8),
                textAlign = TextAlign.Center,
                style = if (compact) MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                ) else MaterialTheme.typography.titleMedium,
            )
            if (actionLabel != null && action != null && actionIcon != null) {
                val label = stringResource(actionLabel)
                IconButton(action, modifier = Modifier.semantics { contentDescription = label }) {
                    Icon(painterResource(actionIcon), null, tint = colors.primary)
                }
            } else Spacer(Modifier.size(SoundScapeTheme.spacing.space48))
        }
    }
}

@Composable
private fun ProfileCard(profile: UserProfile, actions: @Composable () -> Unit) {
    val spacing = SoundScapeTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = SoundScapeTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(profile.displayName, Modifier.size(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                    profile.bio?.takeIf { it.isNotBlank() }?.let { ProfileCaption(it) }
                }
            }
            actions()
        }
    }
}

@Composable
internal fun ProfileAvatar(name: String, modifier: Modifier = Modifier, image: (@Composable () -> Unit)? = null) {
    val colors = SoundScapeTheme.colors
    val initial = name.trim().takeIf { it.isNotEmpty() }
        ?.let { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }
    Surface(modifier, shape = CircleShape, color = colors.primarySoft, contentColor = colors.onPrimarySoft) {
        Box(contentAlignment = Alignment.Center) {
            if (initial != null) Text(initial, style = MaterialTheme.typography.headlineMedium)
            else Icon(painterResource(R.drawable.ic_user), null, Modifier.size(32.dp))
            image?.invoke()
        }
    }
}

@Composable
internal fun ProfileCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = SoundScapeTheme.colors.textMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ProfileRow(
    title: String, subtitle: String?, @DrawableRes icon: Int, danger: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Row(
        Modifier.fillMaxWidth().heightIn(min = spacing.space48).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.space12, vertical = spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
    ) {
        ProfileBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) colors.danger else colors.text, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { ProfileCaption(it) }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

@Composable
private fun ProfileBadge(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val colors = SoundScapeTheme.colors
    Box(
        modifier.size(42.dp).background(colors.primarySoft, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = colors.onPrimarySoft)
    }
}

@Composable
internal fun ProfileStatus(
    @StringRes title: Int, @StringRes detail: Int? = null, danger: Boolean = false,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (danger) MaterialTheme.colorScheme.errorContainer else colors.primarySoft,
        contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else colors.onPrimarySoft,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(spacing.space12),
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (title == R.string.profile_offline) R.drawable.ic_offline else R.drawable.ic_alert
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
internal fun ProfileLoadBody(state: ProfileLoad<*>, back: () -> Unit, retry: () -> Unit, signIn: () -> Unit = {}) {
    if (state == ProfileLoad.Loading) {
        ProfileSkeleton()
        return
    }
    Box(Modifier.fillMaxSize().padding(SoundScapeTheme.spacing.space24), contentAlignment = Alignment.Center) {
        when (state) {
            ProfileLoad.SignIn -> ProfileMessage(R.string.profile_sign_in_title, R.string.profile_sign_in_hint,
                R.drawable.ic_lock, R.string.profile_sign_in, signIn)
            ProfileLoad.OfflineEmpty -> ProfileMessage(
                R.string.profile_offline_empty, R.string.profile_offline_empty_hint, R.drawable.ic_offline,
                R.string.profile_return, back,
            )
            ProfileLoad.Unavailable -> ProfileMessage(R.string.profile_unavailable, R.string.profile_unavailable_hint,
                R.drawable.ic_lock, R.string.profile_return, back)
            ProfileLoad.Error -> ProfileMessage(R.string.profile_load_error, R.string.profile_load_error_hint,
                R.drawable.ic_alert, R.string.profile_retry, retry)
            else -> Unit
        }
    }
}

@Composable
private fun ProfileMessage(
    @StringRes title: Int, @StringRes detail: Int?, @DrawableRes icon: Int,
    @StringRes button: Int? = null, action: () -> Unit = {},
) {
    val spacing = SoundScapeTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = SoundScapeTheme.colors.surface) {
        Column(
            Modifier.fillMaxWidth().padding(spacing.space20),
            verticalArrangement = Arrangement.spacedBy(spacing.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileBadge(icon)
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = SoundScapeTheme.colors.textMuted, textAlign = TextAlign.Center) }
            button?.let { SoundScapeButton(stringResource(it), action, Modifier.fillMaxWidth(), compact = true) }
        }
    }
}

@Composable
private fun ProfileSkeleton() {
    val spacing = SoundScapeTheme.spacing
    val colors = SoundScapeTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space12),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = colors.surface) {
            Column(
                Modifier.fillMaxWidth().padding(spacing.space16),
                verticalArrangement = Arrangement.spacedBy(spacing.space16),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(72.dp).background(colors.primarySoft, CircleShape))
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
                        SkeletonLine(0.65f)
                        SkeletonLine(0.85f)
                    }
                }
                SkeletonLine(1f, Modifier.height(spacing.space40))
            }
        }
        repeat(4) {
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
                Box(Modifier.fillMaxWidth().padding(spacing.space16)) { SkeletonLine(0.62f) }
            }
        }
    }
}

@Composable
private fun SkeletonLine(width: Float, modifier: Modifier = Modifier.height(12.dp)) {
    Box(modifier.fillMaxWidth(width).background(SoundScapeTheme.colors.surfaceAlt, MaterialTheme.shapes.small))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSheet(
    dismiss: () -> Unit, profileActions: Boolean = false, content: @Composable () -> Unit,
) {
    val colors = SoundScapeTheme.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, containerColor = colors.surface,
        dragHandle = {
            if (profileActions) {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 20.dp).size(width = 36.dp, height = 4.dp)
                        .background(colors.outline, CircleShape),
                )
            } else {
                androidx.compose.material3.BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        val spacing = SoundScapeTheme.spacing
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(
                start = spacing.space16, end = spacing.space16, bottom = spacing.space16,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.space12),
        ) { content() }
    }
}

@Composable
internal fun ProfileSheetTitle(title: String, @DrawableRes icon: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = SoundScapeTheme.colors.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ProfileConfirmSheet(
    @StringRes title: Int, @StringRes detail: Int, @StringRes positive: Int, @StringRes negative: Int,
    dismiss: () -> Unit, confirm: () -> Unit,
) {
    ProfileSheet(dismiss) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        ProfileCaption(stringResource(detail))
        SoundScapeButton(
            stringResource(positive), confirm, Modifier.fillMaxWidth(), SoundScapeButtonVariant.Destructive,
        )
        SoundScapeButton(stringResource(negative), dismiss, Modifier.fillMaxWidth(), SoundScapeButtonVariant.Outlined)
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun PersonalProfilePreview() {
    SoundScapeTheme {
        PersonalProfileScreen(
            ProfileLoad.Content(PreviewProfile), PersonalProfileActions({}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun PublicProfilePreview() {
    SoundScapeTheme {
        PublicProfileScreen(
            ProfileLoad.Content(PublicProfile(PreviewProfile, emptyList(), emptyList())), {}, {}, { _, _, _ -> },
        )
    }
}

internal val PreviewProfile = UserProfile(
    UUID.fromString("11111111-1111-4111-8111-111111111111"), "Александр",
    "Собираю музыку, которая напоминает о путешествиях.",
)
