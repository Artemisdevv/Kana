package com.artemis.ebookreader.feature.settings

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.dictionary.WORDNET_LICENSE_ASSET
import com.artemis.ebookreader.data.preferences.ApplicationThemeSettings
import com.artemis.ebookreader.data.preferences.LibraryLayout
import com.artemis.ebookreader.data.preferences.ReaderAppearanceSettings
import com.artemis.ebookreader.data.preferences.ReaderFont
import com.artemis.ebookreader.feature.library.LibraryLayoutOptions
import com.artemis.ebookreader.feature.library.LibrarySortOptions
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.ui.Motion
import java.io.IOException
import kotlinx.coroutines.CoroutineScope

internal enum class SettingsPage(
    @param:StringRes @get:StringRes val title: Int,
    @param:StringRes @get:StringRes val summary: Int,
    @param:DrawableRes @get:DrawableRes val icon: Int,
) {
    Appearance(R.string.appearance, R.string.settings_appearance_summary, R.drawable.ic_contrast),
    Reading(R.string.reading_preferences, R.string.settings_reading_summary, R.drawable.ic_book),
    Library(R.string.settings_library_preferences, R.string.settings_library_summary, R.drawable.ic_grid),
    Backup(R.string.settings_backup, R.string.settings_coming_soon, R.drawable.ic_settings_backup),
    Storage(R.string.settings_storage, R.string.settings_storage_summary, R.drawable.ic_settings_storage),
    About(R.string.settings_about, R.string.settings_about_summary, R.drawable.ic_settings_info),
    Licenses(R.string.settings_licenses, R.string.settings_licenses_summary, R.drawable.ic_settings_license),
}

@Composable
internal fun SettingsScreen(
    page: SettingsPage?,
    readerAppearance: ReaderAppearanceSettings,
    appAppearance: ApplicationThemeSettings,
    library: LibraryViewModel,
    libraryLayout: LibraryLayout,
    scope: CoroutineScope,
    bottomPadding: Dp,
    onOpenPage: (SettingsPage) -> Unit,
    importing: Boolean,
    onImportBook: () -> Unit,
    selectedPage: SettingsPage? = null,
    wide: Boolean = false,
) {
    if (page == SettingsPage.Reading) {
        ReadingPreferences(readerAppearance, scope, Modifier.fillMaxSize(), bottomPadding = bottomPadding)
        return
    }
    SettingsColumn(bottomPadding) {
        when (page) {
            null -> SettingsLanding(selectedPage, onOpenPage)
            SettingsPage.Appearance -> ApplicationAppearance(appAppearance, scope)
            SettingsPage.Library -> SettingsGroup(R.string.settings_library_display) {
                LibraryLayoutOptions(
                    libraryLayout, library::setViewMode, library::setGridColumns, showDividers = true, wide = wide,
                )
                SettingsDivider()
                LibrarySortOptions(library)
            }
            SettingsPage.Backup -> SettingsLanding(null, onOpenPage)
            SettingsPage.Storage -> StorageSettings(importing, onImportBook)
            SettingsPage.About -> ApplicationInfo()
            SettingsPage.Licenses -> OpenSourceLicenses()
            SettingsPage.Reading -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsSplitPane(
    page: SettingsPage?,
    directive: PaneScaffoldDirective,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val value = calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = 2,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
        currentDestination = ThreePaneScaffoldDestinationItem(
            if (page == null) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail,
            page,
        ),
    )
    ListDetailPaneScaffold(
        directive = directive.copy(maxHorizontalPartitions = 2, horizontalPartitionSpacerSize = 12.dp),
        value = value,
        modifier = Modifier.widthIn(max = 1112.dp).fillMaxSize(),
        listPane = { Column(Modifier.preferredWidth(352.dp).fillMaxSize()) { list() } },
        detailPane = { detail() },
    )
}

@Composable
internal fun SettingsColumn(bottomPadding: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun SettingsGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(title),
            modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}

@Composable
private fun SettingsLanding(selectedPage: SettingsPage?, onOpenPage: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    val version = installedVersion()
    fun pageEntry(page: SettingsPage) = SettingsEntry(page.title, page.summary, page.icon, page) { onOpenPage(page) }
    fun externalEntry(@StringRes title: Int, @StringRes summary: Int, @DrawableRes icon: Int, url: String?): SettingsEntry =
        SettingsEntry(title, if (url == null) R.string.settings_feature_unavailable else summary, icon, action = url?.let { destination ->
            {
                try {
                    browser.openUri(destination)
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        })
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.kana_foreground),
                contentDescription = null,
                modifier = Modifier.size(144.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            )
        }
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            version?.let { stringResource(R.string.settings_brand_version, it) } ?: stringResource(R.string.settings_version_unavailable),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsPageGroup(R.string.settings_preferences_group,
        listOf(pageEntry(SettingsPage.Appearance), pageEntry(SettingsPage.Reading), pageEntry(SettingsPage.Library)), selectedPage)
    SettingsPageGroup(R.string.settings_data_group, listOf(
        pageEntry(SettingsPage.Storage), SettingsEntry(SettingsPage.Backup.title, SettingsPage.Backup.summary, SettingsPage.Backup.icon),
    ), selectedPage)
    SettingsPageGroup(R.string.about, listOf(
        SettingsEntry(R.string.settings_updates, R.string.settings_coming_soon, R.drawable.ic_settings_updates),
        externalEntry(R.string.settings_github, R.string.settings_github_summary, R.drawable.ic_settings_code, SettingsLinks.repository),
        externalEntry(R.string.settings_issue, R.string.settings_issue_summary, R.drawable.ic_settings_issue, SettingsLinks.issues),
        pageEntry(SettingsPage.Licenses), pageEntry(SettingsPage.About),
    ), selectedPage)
}

private data class SettingsEntry(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:DrawableRes val icon: Int,
    val page: SettingsPage? = null,
    val action: (() -> Unit)? = null,
)

@Composable
private fun SettingsPageGroup(
    @StringRes heading: Int,
    entries: List<SettingsEntry>,
    selectedPage: SettingsPage?,
) {
    SettingsGroup(heading) {
        for ((index, entry) in entries.withIndex()) {
            val isSelected = entry.page != null && entry.page == selectedPage
            val colors = MaterialTheme.colorScheme
            val rowColor by animateColorAsState(
                if (isSelected) colors.secondaryContainer else Color.Transparent,
                Motion.standard(Motion.Feedback),
                label = "Settings selection",
            )
            if (index > 0) SettingsDivider()
            ListItem(
                headlineContent = { Text(stringResource(entry.title)) },
                supportingContent = { Text(stringResource(entry.summary)) },
                leadingContent = { Icon(painterResource(entry.icon), contentDescription = null) },
                trailingContent = entry.action?.let { { Icon(painterResource(R.drawable.ic_settings_chevron), contentDescription = null) } },
                modifier = Modifier.then(entry.action?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
                    .then(if (isSelected) Modifier.semantics { selected = true } else Modifier),
                colors = ListItemDefaults.colors(
                    containerColor = rowColor,
                    headlineColor = if (isSelected) colors.onSecondaryContainer else if (entry.action == null) colors.onSurfaceVariant else colors.onSurface,
                    supportingColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    leadingIconColor = if (isSelected) colors.onSecondaryContainer else if (entry.action == null) colors.onSurfaceVariant else colors.primary,
                    trailingIconColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun SettingsDetail(@StringRes title: Int, detail: String) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(detail) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Suppress("DEPRECATION")
@Composable
private fun installedVersion(): String? {
    val context = LocalContext.current
    return remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
}

@Composable
private fun ApplicationInfo() {
    val version = installedVersion()
    SettingsGroup(R.string.settings_app_information) {
        SettingsDetail(R.string.app_name, stringResource(R.string.settings_app_description))
        SettingsDivider()
        SettingsDetail(R.string.settings_version, version ?: stringResource(R.string.settings_version_unavailable))
    }
}

private data class License(val name: String, val identifier: String, val location: String)

private val licenses = listOf(
    License("Readium Kotlin Toolkit", "BSD-3-Clause", "licenses/Readium.txt"),
    License("AndroidX · Jetpack Compose, Room, DataStore", "Apache-2.0", "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"),
    License("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"),
    License("Kotlin coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt"),
    License("Android Java API desugaring", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs/blob/master/LICENSE"),
)

private val toolkitFonts = listOf(
    License("AccessibleDfA", "OFL-1.1", "readium/readium-css/fonts/LICENSE-AccessibleDfa"),
    License("iA Writer Duospace", "OFL-1.1", "readium/readium-css/fonts/LICENSE-IaWriterDuospace.md"),
    License("OpenDyslexic", "CC BY 3.0 and Bitstream", "licenses/OpenDyslexic.txt"),
)

@Composable
private fun OpenSourceLicenses() {
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    var selectedLicense by remember { mutableStateOf<License?>(null) }
    val onSelect: (License) -> Unit = { license ->
        if (license.location.startsWith("https://")) {
            try {
                browser.openUri(license.location)
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, R.string.settings_license_unavailable, Toast.LENGTH_SHORT).show()
            }
        } else {
            selectedLicense = license
        }
    }
    SettingsGroup(R.string.settings_libraries) {
        for ((index, license) in licenses.withIndex()) {
            if (index > 0) SettingsDivider()
            LicenseRow(license, onSelect)
        }
    }
    SettingsGroup(R.string.settings_dictionary_sources) {
        LicenseRow(
            License(
                stringResource(R.string.dictionary_wordnet), "WordNet License",
                WORDNET_LICENSE_ASSET,
            ),
            onSelect,
        )
        SettingsDivider()
        LicenseRow(
            License(stringResource(R.string.dictionary_online_provider), "Wiktionary · CC BY-SA", "https://dictionaryapi.dev/"),
            onSelect,
        )
    }
    SettingsGroup(R.string.settings_bundled_fonts) {
        for ((index, font) in ReaderFont.entries.filter { it.bundled != null }.withIndex()) {
            if (index > 0) SettingsDivider()
            val assetPath = checkNotNull(font.bundled).assetPath
            LicenseRow(License(stringResource(font.label), "OFL-1.1", "$assetPath/OFL.txt"), onSelect)
        }
        for (font in toolkitFonts) {
            SettingsDivider()
            LicenseRow(font, onSelect)
        }
    }
    selectedLicense?.let { license ->
        val licenseText = remember(context, license.location) {
            try {
                context.assets.open(license.location).bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                context.getString(R.string.settings_license_read_failed)
            }
        }
        AlertDialog(
            onDismissRequest = { selectedLicense = null },
            title = { Text(license.name) },
            text = { Text(licenseText, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    onClick = { selectedLicense = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun LicenseRow(license: License, onSelect: (License) -> Unit) {
    ListItem(
        headlineContent = { Text(license.name) },
        supportingContent = { Text(license.identifier) },
        trailingContent = { Icon(painterResource(R.drawable.ic_settings_chevron), contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button) { onSelect(license) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
