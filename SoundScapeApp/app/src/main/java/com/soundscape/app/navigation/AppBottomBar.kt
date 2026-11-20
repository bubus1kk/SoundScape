package com.soundscape.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.soundscape.app.core.designsystem.ui.theme.SoundScapeTheme

@Composable
internal fun AppBottomBar(route: String?, navigate: (AppDestination) -> Unit) {
    val colors = SoundScapeTheme.colors
    Surface(color = colors.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            AppDestination.entries.forEach { destination ->
                val selected = route == destination.route
                val tint = if (selected) colors.primary else colors.textMuted
                Column(Modifier.weight(1f).heightIn(min = 56.dp)
                    .selectable(selected, role = Role.Tab) { navigate(destination) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.padding(top = 7.dp, bottom = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(destination.icon), null, Modifier.size(21.dp), tint = tint)
                        Text(stringResource(destination.label), style = MaterialTheme.typography.bodySmall,
                            color = tint, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }
}
