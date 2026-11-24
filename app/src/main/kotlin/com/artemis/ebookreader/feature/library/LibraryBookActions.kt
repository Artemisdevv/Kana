package com.artemis.ebookreader.feature.library

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.BookFormat
import com.artemis.ebookreader.data.library.RenameResult
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.data.storage.filenameStem
import com.artemis.ebookreader.data.storage.currentFilename
import com.artemis.ebookreader.data.storage.suggestedExportName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class LibraryBookAction(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Select(R.string.library_select_book, R.drawable.ic_select_all),
    Rename(R.string.library_rename, R.drawable.ic_book_rename),
    Details(R.string.library_book_details, R.drawable.ic_settings_info),
    Export(R.string.library_export, R.drawable.ic_book_export),
    Reset(R.string.library_reset_progress, R.drawable.ic_history),
    Delete(R.string.library_delete_button, R.drawable.ic_remove_history),
}

internal fun ShelfBook.createExportDocument(fallbackName: String): Intent? {
    val type = BookFormat.fromStorage(format) ?: return null
    val name = suggestedExportName(book.sourceName, book.title, type.extension, fallbackName)
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        this.type = type.mimeType
        putExtra(Intent.EXTRA_TITLE, name)
    }
}

@Composable
internal fun BookActionAnchor(
    book: ShelfBook,
    onAction: (ShelfBook, LibraryBookAction) -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    content: @Composable (onLongClick: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        content { if (!selectionMode) expanded = true }
        DropdownMenu(expanded = expanded && !selectionMode, onDismissRequest = { expanded = false }) {
            for (action in LibraryBookAction.entries) {
                if (action == LibraryBookAction.Delete) HorizontalDivider()
                val destructive = action == LibraryBookAction.Delete
                val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                DropdownMenuItem(
                    text = {
                        if (destructive) Text(stringResource(action.label), color = tint)
                        else Text(stringResource(action.label))
                    },
                    onClick = {
                        expanded = false
                        onAction(book, action)
                    },
                    leadingIcon = { Icon(painterResource(action.icon), contentDescription = null, tint = tint) },
                    enabled = (action != LibraryBookAction.Select && action != LibraryBookAction.Export) || book.available,
                )
            }
        }
    }
}

@Composable
internal fun LibraryBookDialog(
    book: ShelfBook,
    action: LibraryBookAction,
    library: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    when (action) {
        LibraryBookAction.Rename -> RenameBookDialog(book, library, onDismiss)
        LibraryBookAction.Delete, LibraryBookAction.Reset -> {
            val delete = action == LibraryBookAction.Delete
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(if (delete) R.string.library_delete else action.label)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val name = currentFilename(book.book.sourceName, book.format) ?: book.book.title.takeUnless(String::isBlank)
                        name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        Text(stringResource(if (delete) R.string.library_delete_confirm else R.string.library_reset_confirm))
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
                confirmButton = {
                    val colors = if (delete) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.textButtonColors()
                    TextButton(
                        colors = colors,
                        onClick = {
                            onDismiss()
                            if (delete) library.deleteBook(book.book.bookId) else library.resetBookProgress(book.book.bookId)
                        },
                    ) { Text(stringResource(if (delete) R.string.library_delete_button else R.string.library_reset_button)) }
                },
            )
        }
        LibraryBookAction.Select, LibraryBookAction.Details, LibraryBookAction.Export -> Unit
    }
}

@Composable
private fun RenameBookDialog(book: ShelfBook, library: LibraryViewModel, onDismiss: () -> Unit) {
    val format = BookFormat.fromStorage(book.format) ?: return
    var name by rememberSaveable(book.book.bookId) {
        mutableStateOf(filenameStem(book.book.sourceName, format.extension).orEmpty())
    }
    var error by rememberSaveable(book.book.bookId) { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.library_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_rename_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                        label = { Text(stringResource(R.string.library_filename)) },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Text(".${format.extension}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            error = when (library.renameBook(book.book.bookId, name)) {
                                RenameResult.InvalidName -> R.string.library_rename_invalid
                                RenameResult.Conflict -> R.string.library_rename_conflict
                                RenameResult.Missing -> R.string.book_missing
                                RenameResult.Renamed -> {
                                    library.reportMessage(R.string.library_renamed)
                                    onDismiss()
                                    null
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = R.string.library_rename_failed
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.library_rename_button)) }
        },
    )
}
