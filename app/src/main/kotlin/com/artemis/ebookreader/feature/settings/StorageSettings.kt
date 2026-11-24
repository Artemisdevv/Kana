package com.artemis.ebookreader.feature.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.artemis.ebookreader.R
import com.artemis.ebookreader.app.EBookReaderApplication
import com.artemis.ebookreader.data.library.IntegrityIssue
import com.artemis.ebookreader.data.library.IntegrityIssueKind
import com.artemis.ebookreader.data.library.StorageCategory
import com.artemis.ebookreader.data.library.StorageOverview

@Composable
internal fun StorageSettings(importing: Boolean, onImportBook: () -> Unit) {
    val app = LocalContext.current.applicationContext as EBookReaderApplication
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val model = remember(owner, app) {
        ViewModelProvider(owner, viewModelFactory { initializer { StorageViewModel(app.library) } })[StorageViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model, importing) { if (!importing || model.state.value.overview == null) model.refresh() }
    val overview = state.overview
    val busy = state.busy
    val canClear = busy == null && overview != null && overview.removableFiles > 0

    SettingsGroup(R.string.storage_overview) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                overview?.let { storageSize(it.totalBytes) } ?: stringResource(R.string.storage_not_measured),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
            )
            IconButton(onClick = model::refresh, enabled = busy == null) {
                Icon(painterResource(R.drawable.ic_settings_updates), stringResource(R.string.storage_refresh))
            }
        }
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            StorageCaption(
                when (busy) {
                    StorageTask.Refresh -> R.string.storage_measuring
                    StorageTask.Inspect -> R.string.storage_checking
                    StorageTask.Clear -> R.string.storage_clearing
                },
            )
        }
        state.failed?.let {
            StorageCaption(when (it) {
                StorageTask.Refresh -> R.string.storage_measure_failed
                StorageTask.Inspect -> R.string.storage_check_failed
                StorageTask.Clear -> R.string.storage_clear_failed
            }, error = true)
        }
        if (overview != null) StorageBreakdown(overview)
        StorageCaption(R.string.storage_scope)
        StorageCaption(R.string.storage_dictionary_scope)
    }
    SettingsGroup(R.string.storage_library) {
        StorageRow(
            R.string.storage_imported,
            overview?.let {
                val summary = stringResource(
                    R.string.storage_imported_size,
                    pluralStringResource(R.plurals.storage_imported_count, it.importedBooks, it.importedBooks),
                    storageSize(it.importedBytes),
                )
                if (it.booksOnDevice == it.importedBooks) summary else {
                    "$summary\n" + pluralStringResource(R.plurals.storage_books_on_device, it.booksOnDevice, it.booksOnDevice)
                }
            } ?: stringResource(R.string.storage_not_measured),
        )
        SettingsDivider()
        StorageRow(R.string.storage_available, overview?.let { storageSize(it.availableBytes) } ?: stringResource(R.string.storage_not_measured))
    }
    SettingsGroup(R.string.storage_maintenance) {
        val available = when {
            overview == null -> stringResource(R.string.storage_not_measured)
            overview.removableFiles == 0 -> stringResource(R.string.storage_no_cache)
            else -> stringResource(R.string.storage_cache_available, storageSize(overview.removableBytes))
        }
        StorageRow(R.string.storage_clear, available, action = { confirm = true }, enabled = canClear)
        SettingsDivider()
        StorageRow(
            R.string.storage_check,
            stringResource(R.string.storage_check_summary),
            action = model::inspect,
            enabled = busy == null,
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.storage_clear)) },
            text = {
                Text(stringResource(R.string.storage_confirm_clear, storageSize(overview?.removableBytes ?: 0)))
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = {
                TextButton(onClick = { confirm = false; model.clear() }, enabled = canClear) {
                    Text(stringResource(R.string.storage_clear))
                }
            },
        )
    }
    state.cleanup?.takeIf { busy == null }?.let { result ->
        AlertDialog(
            onDismissRequest = model::dismissCleanup,
            title = { Text(stringResource(R.string.storage_cache_result)) },
            text = {
                val removed = storageSize(result.bytes)
                Text(
                    when {
                        result.failed > 0 -> stringResource(R.string.storage_clear_partial, removed, result.failed)
                        result.removed == 0 -> stringResource(R.string.storage_no_cache)
                        else -> stringResource(R.string.storage_cleared, removed)
                    },
                )
            },
            confirmButton = { TextButton(model::dismissCleanup) { Text(stringResource(R.string.close)) } },
        )
    }
    state.issues?.let { issues ->
        IntegrityResults(
            issues, model::dismissIssues,
            onClear = if (canClear && issues.any { it.kind == IntegrityIssueKind.AbandonedImports }) {
                { model.dismissIssues(); confirm = true }
            } else null,
            onReimport = if (!importing && issues.any {
                    it.kind == IntegrityIssueKind.Missing || it.kind == IntegrityIssueKind.Mismatched
                }) {
                { model.dismissIssues(); onImportBook() }
            } else null,
        )
    }
}

@Composable
private fun StorageBreakdown(overview: StorageOverview) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val groups = StorageCategory.entries.filter { it != StorageCategory.Other || overview.sizes.getValue(it) > 0 }
    val colors = groups.associateWith { category ->
        val hue = when (category) {
            StorageCategory.Books -> if (dark) Color(0xFF9CB8FB) else Color(0xFF3D65B7)
            StorageCategory.Covers -> if (dark) Color(0xFF7AD4B4) else Color(0xFF277A66)
            StorageCategory.Dictionary -> if (dark) Color(0xFFF5CA72) else Color(0xFF9C630D)
            StorageCategory.Cache -> if (dark) Color(0xFFF098AE) else Color(0xFFA54260)
            StorageCategory.ReadingData -> if (dark) Color(0xFFC7A3EC) else Color(0xFF74519D)
            StorageCategory.Other -> if (dark) Color(0xFFA9B2C2) else Color(0xFF626C7D)
        }
        lerp(hue, scheme.primary, 0.10f)
    }
    Canvas(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
            .height(14.dp).clip(CircleShape).background(scheme.surfaceContainerHighest),
    ) {
        val total = overview.totalBytes
        if (total > 0) {
            var running = 0L
            for (category in groups) {
                val start = (size.width * (running.toDouble() / total)).toFloat()
                running += overview.sizes.getValue(category)
                val end = (size.width * (running.toDouble() / total)).toFloat()
                if (end > start) drawRect(colors.getValue(category), Offset(start, 0f), Size(end - start, size.height))
            }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (category in groups) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).background(colors.getValue(category), CircleShape))
                Text(stringResource(category.label()), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(storageSize(overview.sizes.getValue(category)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun IntegrityResults(
    issues: List<IntegrityIssue>,
    onClose: () -> Unit,
    onClear: (() -> Unit)?,
    onReimport: (() -> Unit)?,
) {
    val groups = remember(issues) { issues.groupBy(IntegrityIssue::kind) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.storage_integrity_results)) },
        text = {
            if (issues.isEmpty()) {
                Text(stringResource(R.string.storage_integrity_success))
            } else {
                LazyColumn(Modifier.heightIn(max = 390.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (onReimport != null) {
                        item { TextButton(onReimport) { Text(stringResource(R.string.storage_reimport)) } }
                    }
                    for (kind in IntegrityIssueKind.entries) {
                        val records = groups[kind] ?: continue
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    stringResource(R.string.storage_issue_group, stringResource(kind.label()), records.size),
                                    Modifier.padding(top = 5.dp).semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val total = records.mapNotNull { it.bytes }.sum()
                                if (records.any { it.bytes != null }) {
                                    Text(stringResource(R.string.storage_issue_bytes, storageSize(total)), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(kind.guidance()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(records) { record ->
                            Text(record.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        dismissButton = onClear?.let { { TextButton(it) { Text(stringResource(R.string.storage_clear)) } } },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun StorageRow(@StringRes title: Int, description: String, action: (() -> Unit)? = null, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(description) },
        modifier = if (action == null) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = action),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        ),
    )
}

@Composable
private fun StorageCaption(@StringRes label: Int, error: Boolean = false) {
    Text(
        stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun storageSize(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

@StringRes
private fun StorageCategory.label(): Int = when (this) {
    StorageCategory.Books -> R.string.storage_books
    StorageCategory.Covers -> R.string.storage_covers
    StorageCategory.Dictionary -> R.string.storage_dictionary
    StorageCategory.Cache -> R.string.storage_cache
    StorageCategory.ReadingData -> R.string.storage_reading
    StorageCategory.Other -> R.string.storage_other
}

@StringRes
private fun IntegrityIssueKind.label(): Int = when (this) {
    IntegrityIssueKind.Missing -> R.string.storage_missing
    IntegrityIssueKind.Mismatched -> R.string.storage_mismatched
    IntegrityIssueKind.UnreferencedBooks -> R.string.storage_unreferenced
    IntegrityIssueKind.OrphanedCovers -> R.string.storage_orphaned_covers
    IntegrityIssueKind.AbandonedImports -> R.string.storage_abandoned
    IntegrityIssueKind.PendingDeletion -> R.string.storage_pending
    IntegrityIssueKind.Unexpected -> R.string.storage_unexpected
}

@StringRes
private fun IntegrityIssueKind.guidance(): Int = when (this) {
    IntegrityIssueKind.Missing, IntegrityIssueKind.Mismatched -> R.string.storage_missing_guidance
    IntegrityIssueKind.UnreferencedBooks, IntegrityIssueKind.OrphanedCovers -> R.string.storage_recovery_guidance
    IntegrityIssueKind.AbandonedImports -> R.string.storage_abandoned_guidance
    IntegrityIssueKind.PendingDeletion -> R.string.storage_pending_guidance
    IntegrityIssueKind.Unexpected -> R.string.storage_unexpected_guidance
}
