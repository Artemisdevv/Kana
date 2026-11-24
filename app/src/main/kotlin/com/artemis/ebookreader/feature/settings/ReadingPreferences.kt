package com.artemis.ebookreader.feature.settings

import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.ReaderAlignment
import com.artemis.ebookreader.data.preferences.ReaderAppearanceSettings
import com.artemis.ebookreader.data.preferences.ReaderFont
import com.artemis.ebookreader.data.preferences.ReaderLayout
import com.artemis.ebookreader.data.preferences.ReaderScale
import com.artemis.ebookreader.data.preferences.ReaderTheme
import com.artemis.ebookreader.data.preferences.ReaderWeight
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.transitionInput
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class ReadingPreferenceTab(@param:StringRes @get:StringRes val label: Int) {
    Appearance(R.string.appearance), Text(R.string.reader_text), Layout(R.string.reader_layout),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingPreferencesSheet(
    settings: ReaderAppearanceSettings,
    scope: CoroutineScope,
    initialTab: ReadingPreferenceTab,
    fullscreen: Boolean,
    dark: Boolean,
    reflowable: Boolean,
    verticalText: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.10f),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightNavigationBars = !dark
                    isAppearanceLightStatusBars = !dark
                    if (fullscreen) {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
        ReadingPreferences(
            settings, scope, Modifier.fillMaxHeight(0.92f), initialTab, reflowable = reflowable,
            verticalText = verticalText, inReader = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingPreferences(
    settings: ReaderAppearanceSettings,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    initialTab: ReadingPreferenceTab = ReadingPreferenceTab.Appearance,
    bottomPadding: Dp = 0.dp,
    reflowable: Boolean = true,
    verticalText: Boolean = false,
    inReader: Boolean = false,
) {
    val preferences by settings.appearance.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var saveFailed by remember { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    fun save(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
                saveFailed = false
            } catch (_: IOException) {
                saveFailed = true
            }
        }
    }

    val current = preferences ?: return
    fun edit(change: (ReaderLayout) -> ReaderLayout) = save { settings.updateLayout(change) }
    fun setTheme(theme: ReaderTheme) = save { settings.setTheme(theme) }
    fun setFullscreen(enabled: Boolean) = save { settings.setFullscreen(enabled) }
    fun setPageNumbers(enabled: Boolean) = save { settings.setShowPageNumbers(enabled) }
    fun setKeepScreenAwake(enabled: Boolean) = save { settings.setKeepScreenAwake(enabled) }

    if (inReader) Column(modifier.fillMaxWidth()) {
        PublisherStylesCard(current.layout.publisherStyles) { value ->
            edit { it.copy(publisherStyles = value) }
        }
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            for (choice in ReadingPreferenceTab.entries) {
                Tab(selected = choice == tab, onClick = { tab = choice }, text = { Text(stringResource(choice.label)) })
            }
        }
        val tabState = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { Motion.fadeThrough(Motion.Panel).using(null) },
            label = "Reader preference tab",
        ) { displayedTab ->
            tabState.SaveableStateProvider(displayedTab) {
                Column(
                    Modifier.fillMaxSize().transitionInput(displayedTab == tab).verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (displayedTab) {
                        ReadingPreferenceTab.Appearance -> AppearanceOptions(
                            current.theme, current.fullscreen, current.showPageNumbers, inReader,
                            onTheme = ::setTheme,
                            onFullscreen = ::setFullscreen,
                            onPageNumbers = ::setPageNumbers,
                        )
                        ReadingPreferenceTab.Text -> if (reflowable) TextOptions(current.layout, ::edit) else FixedLayoutNote()
                        ReadingPreferenceTab.Layout -> {
                            if (reflowable) LayoutOptions(current.layout, verticalText, ::edit) else FixedLayoutNote()
                            ReadingBehaviorOptions(current.keepScreenAwake, ::setKeepScreenAwake)
                        }
                    }
                    if (saveFailed) {
                        Text(stringResource(R.string.reader_preference_save_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    } else SettingsColumn(bottomPadding, modifier) {
        SettingsGroup(R.string.appearance) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceOptions(
                    current.theme, current.fullscreen, current.showPageNumbers, inReader = false,
                    onTheme = ::setTheme, onFullscreen = ::setFullscreen, onPageNumbers = ::setPageNumbers,
                )
            }
        }
        PublisherStylesCard(current.layout.publisherStyles) { value ->
            edit { it.copy(publisherStyles = value) }
        }
        SettingsGroup(R.string.reader_text) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextOptions(current.layout, ::edit, grouped = true)
            }
        }
        SettingsGroup(R.string.reader_layout) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutOptions(current.layout, verticalText = false, edit = ::edit, grouped = true)
                SettingsDivider()
                PageNumbersOption(current.showPageNumbers, ::setPageNumbers, grouped = true)
            }
        }
        ReadingBehaviorOptions(current.keepScreenAwake, ::setKeepScreenAwake)
        SettingsGroup(R.string.settings_reset_group) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.reader_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_reset_summary)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(role = Role.Button) { confirmReset = true },
            )
        }
        if (saveFailed) Text(stringResource(R.string.reader_preference_save_error), color = MaterialTheme.colorScheme.error)
    }
    if (confirmReset) {
        val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reader_reset)) },
            text = { Text(stringResource(R.string.reader_reset_message)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; save { settings.reset() } }, colors = actionColors) {
                    Text(stringResource(R.string.reader_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, colors = actionColors) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AppearanceOptions(
    theme: ReaderTheme,
    fullscreen: Boolean,
    showPageNumbers: Boolean,
    inReader: Boolean,
    onTheme: (ReaderTheme) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onPageNumbers: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.reader_theme), style = MaterialTheme.typography.titleSmall)
    val systemDark = isSystemInDarkTheme()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val followsSystem = theme == ReaderTheme.FollowSystem
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(followsSystem, role = Role.RadioButton) { onTheme(ReaderTheme.FollowSystem) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = followsSystem, onClick = null)
            Text(stringResource(R.string.reader_follow_system))
        }
        for (pair in ReaderTheme.entries.drop(1).chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in pair) {
                    val selected = choice == theme
                    val palette = choice.resolve(systemDark)
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                            .selectable(selected, role = Role.RadioButton) { onTheme(choice) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color(palette.background),
                        contentColor = Color(palette.text),
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.reader_theme_sample), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(choice.label), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (!inReader) SettingsDivider()
    ListItem(
        headlineContent = { Text(stringResource(R.string.reader_fullscreen)) },
        supportingContent = if (inReader) ({ Text(stringResource(R.string.reader_fullscreen_next_open)) }) else null,
        trailingContent = { Switch(checked = fullscreen, onCheckedChange = null) },
        modifier = Modifier.toggleable(fullscreen, role = Role.Switch, onValueChange = onFullscreen),
        colors = if (inReader) ListItemDefaults.colors() else ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (inReader) PageNumbersOption(showPageNumbers, onPageNumbers)
}

@Composable
private fun PageNumbersOption(showPageNumbers: Boolean, onPageNumbers: (Boolean) -> Unit, grouped: Boolean = false) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.reader_show_page_numbers)) },
        supportingContent = { Text(stringResource(R.string.reader_page_numbers_hint)) },
        trailingContent = { Switch(checked = showPageNumbers, onCheckedChange = null) },
        modifier = Modifier.toggleable(showPageNumbers, role = Role.Switch, onValueChange = onPageNumbers),
        colors = if (grouped) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors(),
    )
}

@Composable
private fun ReadingBehaviorOptions(
    keepScreenAwake: Boolean,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    grouped: Boolean = false,
) {
    Text(stringResource(R.string.reader_behavior), style = MaterialTheme.typography.titleSmall)
    ListItem(
        headlineContent = { Text(stringResource(R.string.reader_keep_screen_awake)) },
        supportingContent = { Text(stringResource(R.string.reader_keep_screen_awake_hint)) },
        trailingContent = { Switch(checked = keepScreenAwake, onCheckedChange = null) },
        modifier = Modifier.toggleable(keepScreenAwake, role = Role.Switch) { value ->
            onKeepScreenAwakeChange(value)
        },
        colors = if (grouped) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors(),
    )
}

@Composable
private fun TextOptions(layout: ReaderLayout, edit: ((ReaderLayout) -> ReaderLayout) -> Unit, grouped: Boolean = false) {
    ChoiceMenu(R.string.reader_font_family, layout.font, ReaderFont.entries,
        label = { stringResource(it.label) }, preview = { it.preview() }, markSelection = true,
    ) { value -> edit { it.copy(font = value) } }
    if (grouped) SettingsDivider()
    ScaleControl(R.string.reader_font_size, ReaderScale.FontSize, layout.fontSize, R.string.reader_percent,
        incrementButtons = true) { value -> edit { it.copy(fontSize = requireNotNull(value)) } }
    if (grouped) SettingsDivider()
    ChoiceMenu(R.string.reader_font_weight, layout.fontWeight, ReaderWeight.entries,
        label = { stringResource(it.label()) }) { value -> edit { it.copy(fontWeight = value) } }
    if (grouped) SettingsDivider()
    if (layout.publisherStyles) PublisherOverrideNote()
    ScaleControl(R.string.reader_line_spacing, ReaderScale.LineHeight, layout.lineHeight, R.string.reader_multiplier,
        enabled = !layout.publisherStyles, canUseDefault = true) { value -> edit { it.copy(lineHeight = value) } }
    if (grouped) SettingsDivider()
    ScaleControl(R.string.reader_paragraph_spacing, ReaderScale.ParagraphSpacing, layout.paragraphSpacing, R.string.reader_em,
        enabled = !layout.publisherStyles, canUseDefault = true) { value -> edit { it.copy(paragraphSpacing = value) } }
}

@Composable
private fun PublisherStylesCard(
    publisherStyles: Boolean,
    onPublisherStyles: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.reader_publisher_styles)) },
                supportingContent = { Text(stringResource(R.string.reader_publisher_styles_hint)) },
                trailingContent = { Switch(checked = publisherStyles, onCheckedChange = null) },
                modifier = Modifier.toggleable(publisherStyles, role = Role.Switch, onValueChange = onPublisherStyles),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun LayoutOptions(layout: ReaderLayout, verticalText: Boolean, edit: ((ReaderLayout) -> ReaderLayout) -> Unit, grouped: Boolean = false) {
    ChoiceMenu(R.string.reader_reading_mode, layout.scroll || verticalText, listOf(false, true),
        label = { stringResource(if (it) R.string.reader_continuous else R.string.reader_paginated) },
        enabled = !verticalText) { value ->
        edit { it.copy(scroll = value) }
    }
    if (verticalText) Text(stringResource(R.string.reader_vertical_text_note), style = MaterialTheme.typography.bodySmall)
    ScaleControl(R.string.reader_horizontal_margin, ReaderScale.HorizontalMargins, layout.horizontalMargins, R.string.reader_percent) { value ->
        edit { it.copy(horizontalMargins = requireNotNull(value)) }
    }
    if (grouped) SettingsDivider()
    ScaleControl(R.string.reader_vertical_margin, ReaderScale.VerticalMargins, layout.verticalMargins, R.string.reader_dp) { value ->
        edit { it.copy(verticalMargins = requireNotNull(value)) }
    }
    if (grouped) SettingsDivider()
    ChoiceMenu(R.string.reader_text_alignment, layout.alignment, ReaderAlignment.entries,
        label = { stringResource(it.label()) }, enabled = !layout.publisherStyles && !verticalText) { value ->
        edit { it.copy(alignment = value) }
    }
}

@Composable
private fun <T> ChoiceMenu(
    @StringRes title: Int,
    current: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    preview: @Composable (T) -> FontFamily? = { null },
    enabled: Boolean = true,
    markSelection: Boolean = false,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) {
            Text(label(current), fontFamily = preview(current))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (choice in choices) {
                val isSelected = markSelection && choice == current
                DropdownMenuItem(
                    text = { Text(label(choice), fontFamily = preview(choice)) },
                    onClick = { expanded = false; onSelect(choice) },
                    modifier = if (isSelected) Modifier.semantics { selected = true } else Modifier,
                    trailingIcon = if (isSelected) {
                        { RadioButton(selected = true, onClick = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun ScaleControl(
    @StringRes title: Int,
    scale: ReaderScale,
    value: Int?,
    @StringRes format: Int,
    enabled: Boolean = true,
    canUseDefault: Boolean = false,
    incrementButtons: Boolean = false,
    onChange: (Int?) -> Unit,
) {
    var preview by remember { mutableStateOf<Int?>(null) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(preview) {
        val next = preview ?: return@LaunchedEffect
        delay(140)
        change(next)
    }
    val shown = preview ?: value
    val position = shown ?: scale.default
    val label = stringResource(title)
    val formatted = when {
        shown == null -> stringResource(R.string.reader_publisher_default)
        format == R.string.reader_multiplier || format == R.string.reader_em -> stringResource(format, shown / 100.0)
        else -> stringResource(format, shown)
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatted, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (incrementButtons) {
                TextButton(onClick = { onChange((position - scale.step).coerceAtLeast(scale.min)) },
                    modifier = Modifier.heightIn(min = 48.dp), enabled = position > scale.min) { Text("A−") }
            }
            Slider(value = position.toFloat(), onValueChange = { preview = it.roundToInt() },
                onValueChangeFinished = { preview?.let(onChange); preview = null }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label },
                valueRange = scale.min.toFloat()..scale.max.toFloat(), steps = (scale.max - scale.min) / scale.step - 1)
            if (incrementButtons) {
                TextButton(onClick = { onChange((position + scale.step).coerceAtMost(scale.max)) },
                    modifier = Modifier.heightIn(min = 48.dp), enabled = position < scale.max) { Text("A+") }
            }
        }
        if (canUseDefault && enabled && shown != null) {
            TextButton(onClick = { preview = null; onChange(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.reader_use_publisher_default))
            }
        }
    }
}

@Composable
private fun FixedLayoutNote() {
    Text(stringResource(R.string.reader_fixed_layout), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PublisherOverrideNote() {
    Text(stringResource(R.string.reader_publisher_styles_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReaderFont.preview(): FontFamily? = when (this) {
    ReaderFont.Serif -> FontFamily.Serif
    ReaderFont.SansSerif -> FontFamily.SansSerif
    ReaderFont.Monospace, ReaderFont.Duospace -> FontFamily.Monospace
    else -> bundled?.let { font ->
        val assets = LocalContext.current.assets
        remember(assets, font) { FontFamily(Typeface.createFromAsset(assets, "${font.assetPath}/regular.ttf")) }
    }
}

private fun ReaderWeight.label(): Int = when (this) {
    ReaderWeight.Publisher -> R.string.reader_publisher_default
    ReaderWeight.Regular -> R.string.reader_weight_regular
    ReaderWeight.Medium -> R.string.reader_weight_medium
    ReaderWeight.Semibold -> R.string.reader_weight_semibold
}

private fun ReaderAlignment.label(): Int = when (this) {
    ReaderAlignment.Publisher -> R.string.reader_publisher_default
    ReaderAlignment.Left -> R.string.reader_left_aligned
    ReaderAlignment.Justified -> R.string.reader_justified
}
