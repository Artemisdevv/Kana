package com.artemis.ebookreader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySelectionToolbar(
    library: LibraryViewModel,
    selection: LibrarySelection,
    selectableIds: Set<String>,
    query: String?,
    onExport: () -> Unit,
) {
    var confirmDelete by rememberSaveable(selection.session) { mutableStateOf(false) }
    val busy by remember(library) { library.bulkProgress.map { it != null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = library.bulkProgress.value != null)
    val count = selection.ids.size
    val allSelected = selectableIds.isNotEmpty() && count == selectableIds.size
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = { Text(stringResource(R.string.library_selected_count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = library::clearSelection) {
                Icon(painterResource(R.drawable.ic_close_search), stringResource(R.string.library_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = { library.toggleAll(selectableIds) }, enabled = selectableIds.isNotEmpty() && !busy) {
                Icon(
                    painterResource(if (allSelected) R.drawable.ic_book_checked else R.drawable.ic_select_all),
                    stringResource(if (allSelected) R.string.library_deselect_all else R.string.library_select_all),
                )
            }
            IconButton(onClick = onExport, enabled = count > 0 && !busy) {
                Icon(painterResource(R.drawable.ic_book_export), stringResource(R.string.library_export_selected))
            }
            IconButton(onClick = { confirmDelete = true }, enabled = count > 0 && !busy) {
                val tint = if (count > 0 && !busy) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                Icon(painterResource(R.drawable.ic_remove_history), stringResource(R.string.library_delete_selected), tint = tint)
            }
        },
    )
    if (confirmDelete && count > 0) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.library_bulk_delete_title, count, count)) },
            text = { Text(stringResource(R.string.library_bulk_delete_confirm)) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        library.deleteSelection(selection, query)
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.library_delete_button)) }
            },
        )
    }
}

@Composable
internal fun LibraryBulkStatus(library: LibraryViewModel) {
    val progress by library.bulkProgress.collectAsStateWithLifecycle()
    val state = progress ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                state.cancelling -> R.string.library_canceling_export
                state.action == BulkBookAction.Export -> R.string.library_bulk_export_progress
                else -> R.string.library_bulk_delete_progress
            }
            Text(
                stringResource(label, state.processed, state.total),
                Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.action == BulkBookAction.Export) {
                TextButton(enabled = !state.cancelling, onClick = library::cancelBulkExport) {
                    Text(stringResource(R.string.library_cancel))
                }
            }
        }
        LinearProgressIndicator(
            progress = { state.processed.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun LibraryBulkFeedback(library: LibraryViewModel, snackbar: SnackbarHostState) {
    val report by library.bulkReport.collectAsStateWithLifecycle()
    val result = report ?: return
    val summary = when {
        result.cancelled -> R.string.library_bulk_export_cancelled
        result.action == BulkBookAction.Export -> R.string.library_bulk_export_summary
        else -> R.string.library_bulk_delete_summary
    }
    val details = mutableListOf(stringResource(summary, result.successful, result.failed))
    if (result.renamed > 0) details += pluralStringResource(R.plurals.library_bulk_renamed, result.renamed, result.renamed)
    if (result.cleanupPending > 0) {
        val note = if (result.action == BulkBookAction.Export) R.string.library_bulk_export_cleanup else R.string.library_bulk_delete_cleanup
        details += stringResource(note, result.cleanupPending)
    }
    for (issue in result.issues) {
        details += stringResource(
            when (issue) {
                BulkBookIssue.Missing -> R.string.library_bulk_missing
                BulkBookIssue.Permissions -> R.string.library_bulk_permissions
                BulkBookIssue.Destination -> R.string.library_bulk_destination
                BulkBookIssue.Recovery -> R.string.library_bulk_recovery
            },
        )
    }
    LaunchedEffect(result, snackbar) {
        snackbar.showSnackbar(details.joinToString(" "), withDismissAction = true, duration = SnackbarDuration.Long)
        library.bulkReportShown(result)
    }
}
