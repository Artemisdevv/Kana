package com.artemis.ebookreader.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.AppAppearanceMode
import com.artemis.ebookreader.data.preferences.AppColorTheme
import com.artemis.ebookreader.data.preferences.ApplicationThemeSettings
import com.artemis.ebookreader.ui.theme.appColorScheme
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ApplicationAppearance(settings: ApplicationThemeSettings, scope: CoroutineScope) {
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    var failed by remember { mutableStateOf(false) }
    fun save(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
                failed = false
            } catch (_: IOException) {
                failed = true
            }
        }
    }

    SettingsGroup(R.string.settings_app_theme) {
        Column(Modifier.selectableGroup()) {
            for ((index, mode) in AppAppearanceMode.entries.withIndex()) {
                if (index > 0) SettingsDivider()
                val selected = appearance.mode == mode
                ListItem(
                    headlineContent = { Text(stringResource(mode.label)) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected, role = Role.RadioButton) {
                        save { settings.setMode(mode) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    SettingsGroup(R.string.settings_app_amoled) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.app_amoled_background)) },
            supportingContent = { Text(stringResource(R.string.app_amoled_summary)) },
            trailingContent = { Switch(checked = appearance.amoledBlack, onCheckedChange = null) },
            modifier = Modifier.toggleable(appearance.amoledBlack, role = Role.Switch) { enabled ->
                save { settings.setAmoledBlack(enabled) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    SettingsGroup(R.string.settings_app_color_theme) {
        Column(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 540.dp).fillMaxWidth()
                .selectableGroup().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in AppColorTheme.entries.chunked(2)) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (theme in row) {
                        ThemePreviewCard(
                            theme = theme,
                            preview = appColorScheme(theme, dark, appearance.amoledBlack),
                            selected = appearance.colorTheme == theme,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onSelect = { save { settings.setColorTheme(theme) } },
                        )
                    }
                }
            }
        }
    }
    if (failed) Text(stringResource(R.string.settings_app_theme_save_error), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ThemePreviewCard(
    theme: AppColorTheme,
    preview: ColorScheme,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onSelect,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
        contentColor = colors.onSurface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) colors.primary else colors.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeThemePreview(preview)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(theme.label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.size(16.dp)) {
                    if (selected) {
                        Icon(painterResource(R.drawable.ic_theme_selected), contentDescription = null, tint = colors.primary)
                    }
                }
            }
        }
    }
}
