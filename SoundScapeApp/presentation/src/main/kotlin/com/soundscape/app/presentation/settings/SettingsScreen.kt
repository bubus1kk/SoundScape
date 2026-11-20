@file:Suppress("TooManyFunctions", "MagicNumber")

package com.soundscape.app.presentation.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButton
import com.soundscape.app.core.designsystem.ui.components.SoundScapeButtonVariant
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePalettes
import com.soundscape.app.core.designsystem.ui.theme.SoundScapePreset
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme
import com.soundscape.app.core.domain.model.ThemeMode
import com.soundscape.app.core.domain.model.ThemePreset
import com.soundscape.app.core.domain.model.UserSettings
import com.soundscape.app.presentation.R
import com.soundscape.app.presentation.profile.ProfileHeader

enum class SettingsPage(@get:StringRes val title: Int) {
    Overview(R.string.settings_home),
    Appearance(R.string.settings_appearance),
    Cache(R.string.settings_cache),
    Device(R.string.settings_device),
}

data class SettingsActions(
    val back: () -> Unit,
    val open: (SettingsPage) -> Unit,
    val retry: () -> Unit,
    val mode: (ThemeMode) -> Unit,
    val preset: (ThemePreset) -> Unit,
    val dynamic: (Boolean) -> Unit,
    val downloads: () -> Unit,
)

@Composable
fun SettingsScreen(
    page: SettingsPage,
    state: SettingsUiState,
    actions: SettingsActions,
    dynamicColorSupported: Boolean,
    downloadsFailed: Boolean = false,
    offline: Boolean = false,
) {
    val spacing = SoundScapeTheme.spacing
    Column(Modifier.fillMaxSize()) {
        ProfileHeader(page.title, actions.back, compact = true)
        Column(
            Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space20),
        ) {
            val settings = state.settings
            when {
                state.initialLoading -> SettingsLoading()
                settings == null -> SettingsReadError(actions.retry)
                else -> {
                    if (offline) SettingsNotice(R.string.settings_offline, icon = R.drawable.ic_offline)
                    if (state.readFailed) {
                        SettingsNotice(R.string.settings_stale, error = true)
                        SoundScapeButton(stringResource(R.string.settings_retry), actions.retry, compact = true)
                    }
                    if (state.writeFailed && page == SettingsPage.Appearance) {
                        SettingsNotice(R.string.settings_write_error, error = true)
                    }
                    when (page) {
                        SettingsPage.Overview -> SettingsOverview(settings, actions.open)
                        SettingsPage.Appearance -> SettingsAppearance(
                            settings, actions, dynamicColorSupported, !state.saving && !state.readFailed,
                        )
                        SettingsPage.Cache -> SettingsCache(actions.downloads, downloadsFailed)
                        SettingsPage.Device -> SettingsDevice()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsOverview(settings: UserSettings, open: (SettingsPage) -> Unit) {
    SettingsSection(R.string.settings_app) {
        SettingsCard {
            SettingsRow(
                SettingsPage.Appearance.title, R.drawable.ic_palette,
                stringResource(
                    R.string.settings_appearance_summary, presetName(settings.preset), modeName(settings.mode),
                ),
                click = { open(SettingsPage.Appearance) },
            )
            SettingsDivider(insetAfterIcon = true)
            SettingsRow(
                SettingsPage.Cache.title, R.drawable.ic_hard_drive, stringResource(R.string.settings_cache_unavailable),
                click = { open(SettingsPage.Cache) },
            )
            SettingsDivider(insetAfterIcon = true)
            SettingsRow(
                SettingsPage.Device.title, R.drawable.ic_smartphone, stringResource(R.string.settings_device_hint),
                click = { open(SettingsPage.Device) },
            )
            SettingsDivider(insetAfterIcon = true)
            SettingsRow(R.string.settings_sync, R.drawable.ic_refresh_cw, stringResource(R.string.settings_unavailable))
        }
    }
    SettingsSection(R.string.settings_listening) {
        SettingsCard {
            SettingsToggle(R.string.settings_history, R.string.settings_history_hint)
            SettingsDivider()
            SettingsRow(R.string.settings_clear_history, R.drawable.ic_trash_2, danger = true)
        }
        SettingsCaption(R.string.settings_history_unavailable)
    }
}

@Composable
private fun SettingsAppearance(
    settings: UserSettings, actions: SettingsActions, dynamicColorSupported: Boolean, enabled: Boolean,
) {
    val spacing = SoundScapeTheme.spacing
    SettingsSection(R.string.settings_palette) {
        SettingsCaption(R.string.settings_palette_hint)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.space12)) {
            ThemePreset.entries.forEach { preset ->
                PaletteCard(preset, settings.preset == preset, enabled) { actions.preset(preset) }
            }
        }
    }
    SettingsSection(R.string.settings_mode) {
        SettingsSegments(ThemeMode.entries.map { modeName(it) }, settings.mode.ordinal,
            if (enabled) ({ actions.mode(ThemeMode.entries[it]) }) else null)
        SettingsCard {
            SettingsToggle(
                R.string.settings_dynamic,
                if (dynamicColorSupported) R.string.settings_dynamic_hint else R.string.settings_dynamic_unavailable,
                checked = dynamicColorSupported && settings.dynamicColor,
                change = actions.dynamic.takeIf { dynamicColorSupported && enabled },
            )
        }
        if (dynamicColorSupported && settings.dynamicColor) SettingsNotice(R.string.settings_dynamic_active)
    }
}

@Composable
private fun PaletteCard(preset: ThemePreset, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    val (preview, detail) = when (preset) {
        ThemePreset.ATLAS -> SoundScapePreset.Atlas to R.string.settings_atlas_hint
        ThemePreset.AURORA -> SoundScapePreset.Aurora to R.string.settings_aurora_hint
        ThemePreset.VIOLET -> SoundScapePreset.Violet to R.string.settings_violet_hint
    }
    Surface(
        color = colors.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.surfaceAlt),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick = select),
    ) {
        Column(
            Modifier.padding(horizontal = spacing.space16, vertical = spacing.space20),
            verticalArrangement = Arrangement.spacedBy(spacing.space16),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(presetName(preset), style = MaterialTheme.typography.labelLarge, color = colors.text)
                    SettingsCaption(detail)
                }
                Icon(
                    painterResource(if (selected) R.drawable.ic_circle_check else R.drawable.ic_circle),
                    contentDescription = null,
                    modifier = Modifier.size(spacing.space20),
                    tint = if (selected) colors.primary else colors.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space8)) {
                SoundScapePalettes.previewSwatches(preview).forEach { swatch ->
                    Surface(
                        modifier = Modifier.weight(1f).height(36.dp), color = swatch,
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(1.dp, SoundScapePalettes.AtlasLight.surfaceAlt),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun SettingsCache(downloads: () -> Unit, downloadsFailed: Boolean) {
    SettingsSection(R.string.settings_cache_title) {
        SettingsCard {
            SettingsRow(R.string.settings_cache_unavailable, R.drawable.ic_hard_drive)
            SettingsDivider()
            SettingsCaption(
                R.string.settings_cache_hint,
                Modifier.padding(SoundScapeTheme.spacing.space16),
            )
        }
    }
    SettingsSection(R.string.settings_cache_limit) {
        SettingsSegments(listOf(R.string.settings_cache_500, R.string.settings_cache_1, R.string.settings_cache_2)
            .map { stringResource(it) }, null, null)
        SoundScapeButton(
            stringResource(R.string.settings_clear_cache), {},
            Modifier.fillMaxWidth().heightIn(min = SoundScapeTheme.spacing.space48),
            SoundScapeButtonVariant.Outlined, enabled = false, compact = true,
            shape = MaterialTheme.shapes.small,
        )
    }
    SettingsSection(R.string.settings_downloads) {
        SettingsCard {
            SettingsRow(
                R.string.settings_downloads_row, R.drawable.ic_download,
                stringResource(R.string.settings_downloads_hint),
                trailingIcon = R.drawable.ic_external_link,
                click = downloads,
            )
            SettingsDivider()
            SettingsCaption(R.string.settings_downloads_protection, Modifier.padding(SoundScapeTheme.spacing.space12))
        }
        if (downloadsFailed) SettingsNotice(R.string.settings_downloads_error, error = true)
    }
}

@Composable
private fun SettingsDevice() {
    SettingsNotice(R.string.settings_device_unavailable, icon = R.drawable.ic_smartphone)
    SettingsSection(R.string.settings_gestures) {
        SettingsCard {
            SettingsToggle(R.string.settings_shake, R.string.settings_shake_hint, R.drawable.ic_smartphone)
            SettingsDivider(insetAfterIcon = true)
            SettingsToggle(R.string.settings_haptic, R.string.settings_haptic_hint, R.drawable.ic_vibrate)
        }
        SettingsCaption(R.string.settings_shake_scope)
    }
    SettingsSection(R.string.settings_nearby) {
        SettingsCard {
            SettingsToggle(R.string.settings_notify_places, R.string.settings_unavailable, R.drawable.ic_map_pinned)
            SettingsCaption(R.string.settings_nearby_hint, Modifier.padding(SoundScapeTheme.spacing.space16))
        }
    }
    SettingsSection(R.string.settings_playback) {
        SettingsCard {
            SettingsRow(
                R.string.settings_headset, R.drawable.ic_headphones, stringResource(R.string.settings_headset_hint),
            )
        }
        SettingsCaption(R.string.settings_device_note)
    }
}

@Composable
private fun SettingsSegments(labels: List<String>, selected: Int?, select: ((Int) -> Unit)?) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(color = colors.surfaceAlt, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(spacing.space4).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Box(
                    Modifier.weight(1f).heightIn(min = spacing.space40)
                        .background(
                            if (active) colors.surface else colors.surfaceAlt, MaterialTheme.shapes.extraSmall,
                        )
                        .selectable(active, enabled = select != null, role = Role.Tab) { select?.invoke(index) }
                        .padding(horizontal = spacing.space4, vertical = spacing.space8),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (active) colors.primary else colors.textMuted,
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space12)) {
        Text(stringResource(title), color = SoundScapeTheme.colors.text, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = SoundScapeTheme.colors.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsDivider(insetAfterIcon: Boolean = false) {
    val spacing = SoundScapeTheme.spacing
    HorizontalDivider(
        Modifier.padding(
            start = if (insetAfterIcon) spacing.space48 + spacing.space16 else spacing.space16,
            end = spacing.space16,
        ),
        color = SoundScapeTheme.colors.outline.copy(alpha = .48f),
    )
}

@Composable
private fun SettingsRow(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    detail: String? = null,
    danger: Boolean = false,
    @DrawableRes trailingIcon: Int = R.drawable.ic_chevron_right,
    click: (() -> Unit)? = null,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(onClick = { click?.invoke() }, enabled = click != null, color = colors.surface) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = spacing.space48 + spacing.space16).padding(spacing.space12),
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (danger) {
                Icon(painterResource(icon), null, Modifier.size(spacing.space20), tint = colors.danger)
            } else {
                SettingsIcon(icon, colors.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(title), color = if (danger) colors.danger else colors.text,
                    style = MaterialTheme.typography.labelLarge,
                )
                detail?.let { Text(it, color = colors.textMuted, style = MaterialTheme.typography.bodySmall) }
            }
            if (click != null) {
                Icon(painterResource(trailingIcon), null, Modifier.size(spacing.space16), tint = colors.textMuted)
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    @StringRes title: Int,
    @StringRes detail: Int,
    @DrawableRes icon: Int? = null,
    checked: Boolean = false,
    change: ((Boolean) -> Unit)? = null,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Row(
        Modifier.fillMaxWidth().heightIn(min = spacing.space48 + spacing.space16)
            .padding(horizontal = spacing.space12, vertical = spacing.space12),
        horizontalArrangement = Arrangement.spacedBy(spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { SettingsIcon(it, colors.primary) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), color = colors.text, style = MaterialTheme.typography.labelLarge)
            SettingsCaption(detail)
        }
        val label = stringResource(title)
        Switch(
            checked, change ?: {},
            enabled = change != null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = colors.onPrimary,
                uncheckedTrackColor = colors.outline,
                uncheckedThumbColor = colors.surface,
                uncheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = colors.surfaceAlt,
                disabledUncheckedThumbColor = colors.textMuted,
                disabledUncheckedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.semantics { contentDescription = label }.graphicsLayer {
                scaleX = .85f
                scaleY = .85f
            },
        )
    }
}

@Composable
private fun SettingsIcon(@DrawableRes icon: Int, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(SoundScapeTheme.spacing.space40)
            .background(SoundScapeTheme.colors.surfaceAlt, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), null, Modifier.size(SoundScapeTheme.spacing.space20), tint = tint) }
}

@Composable
private fun SettingsNotice(
    @StringRes title: Int, error: Boolean = false, @DrawableRes icon: Int = R.drawable.ic_alert,
) {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else colors.primarySoft,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(spacing.space16),
            horizontalArrangement = Arrangement.spacedBy(spacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = if (error) colors.danger else colors.primary)
            Text(
                stringResource(title), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else colors.onPrimarySoft,
            )
        }
    }
}

@Composable
private fun SettingsCaption(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(stringResource(text), modifier, color = SoundScapeTheme.colors.textMuted,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SettingsLoading() {
    val colors = SoundScapeTheme.colors
    val spacing = SoundScapeTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_loading), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
        )
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    repeat(4) {
        SettingsCard {
            Column(Modifier.padding(spacing.space16), verticalArrangement = Arrangement.spacedBy(spacing.space8)) {
                Box(Modifier.fillMaxWidth(.6f).height(14.dp).background(colors.primarySoft, MaterialTheme.shapes.small))
                Box(Modifier.fillMaxWidth(.85f).height(12.dp).background(colors.surfaceAlt, MaterialTheme.shapes.small))
            }
        }
    }
}

@Composable
private fun SettingsReadError(retry: () -> Unit) {
    SettingsCard {
        Column(
            Modifier.fillMaxWidth().padding(SoundScapeTheme.spacing.space20),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SoundScapeTheme.spacing.space16),
        ) {
            SettingsIcon(R.drawable.ic_alert, SoundScapeTheme.colors.primary)
            Text(
                stringResource(R.string.settings_read_error), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            SoundScapeButton(stringResource(R.string.settings_retry), retry, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable
private fun modeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.LIGHT -> R.string.settings_light
        ThemeMode.DARK -> R.string.settings_dark
        ThemeMode.SYSTEM -> R.string.settings_system
    },
)

private fun presetName(preset: ThemePreset): String = when (preset) {
    ThemePreset.ATLAS -> "Atlas"
    ThemePreset.AURORA -> "Aurora"
    ThemePreset.VIOLET -> "Violet"
}
