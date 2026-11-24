package com.artemis.ebookreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.ResolvedBookmark
import com.artemis.ebookreader.data.library.SavedHighlight
import com.artemis.ebookreader.data.library.HighlightColor
import com.artemis.ebookreader.ui.readingPercentage

private enum class SavedPassageTab(val label: Int) {
    Bookmarks(R.string.reader_bookmarks), Highlights(R.string.saved_highlights), Notes(R.string.saved_notes),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSavedPassages(
    bookmarks: List<ResolvedBookmark>,
    highlights: List<SavedHighlight>?,
    feedback: SnackbarHostState,
    deleting: Boolean,
    onClose: () -> Unit,
    onOpen: (ResolvedBookmark) -> Unit,
    onDelete: (ResolvedBookmark) -> Unit,
    onOpenHighlight: (SavedHighlight) -> Unit,
    onEditNote: (SavedHighlight) -> Unit,
    onRemoveNote: (SavedHighlight) -> Unit,
    onRemoveHighlight: (SavedHighlight) -> Unit,
    onColor: (SavedHighlight, HighlightColor) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(SavedPassageTab.Bookmarks) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        title = { ReaderPopupTitle(R.string.saved_passages) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                    for (choice in SavedPassageTab.entries) {
                        Tab(selected = tab == choice, onClick = { tab = choice }, modifier = Modifier.height(48.dp)) {
                            Text(
                                stringResource(choice.label), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (tab != SavedPassageTab.Bookmarks) {
                    val notes = tab == SavedPassageTab.Notes
                    val entries = if (notes) highlights?.filter { it.record.note != null } else highlights
                    if (entries?.isEmpty() == true) {
                        Text(stringResource(if (notes) R.string.saved_notes_empty else R.string.saved_highlights_empty), Modifier.padding(top = 12.dp))
                    }
                    if (!entries.isNullOrEmpty()) {
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(entries, key = { it.record.highlightId }) { entry ->
                                SavedHighlightRow(entry, notes, onOpenHighlight, onEditNote, onRemoveNote, onRemoveHighlight, onColor)
                            }
                        }
                    }
                } else if (bookmarks.isEmpty()) {
                    Text(stringResource(R.string.reader_bookmarks_empty))
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(bookmarks, key = { it.record.bookmarkId }) { bookmark ->
                            val title = bookmark.record.chapter ?: stringResource(R.string.reader_bookmark_unknown_chapter)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                                        .clickable(role = Role.Button) { onOpen(bookmark) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    bookmark.record.excerpt?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(
                                        bookmark.record.totalProgression?.takeIf { it in 0.0..1.0 }?.let { readingPercentage(it) }
                                            ?: stringResource(R.string.progress_unavailable),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onDelete(bookmark) }, enabled = !deleting) {
                                    Icon(
                                        painterResource(R.drawable.ic_remove_history),
                                        stringResource(R.string.reader_bookmark_delete_description, title),
                                    )
                                }
                            }
                        }
                    }
                }
                ReaderBookmarkFeedback(feedback)
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SavedHighlightRow(
    entry: SavedHighlight,
    notes: Boolean,
    onOpen: (SavedHighlight) -> Unit,
    onEditNote: (SavedHighlight) -> Unit,
    onRemoveNote: (SavedHighlight) -> Unit,
    onRemoveHighlight: (SavedHighlight) -> Unit,
    onColor: (SavedHighlight, HighlightColor) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val record = entry.record
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onOpen(entry) }.padding(vertical = 9.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(3.dp).height(36.dp).background(Color(entry.color.argb), MaterialTheme.shapes.small))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                record.chapter?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(record.selectedText, style = MaterialTheme.typography.bodyMedium, maxLines = if (notes) 2 else 3, overflow = TextOverflow.Ellipsis)
                record.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (notes) 5 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(painterResource(R.drawable.ic_passage_more), stringResource(R.string.saved_passage_actions))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
                if (!notes) {
                    for (color in HighlightColor.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(color.label())) },
                            leadingIcon = {
                                Box(Modifier.width(16.dp).height(16.dp).background(Color(color.argb), MaterialTheme.shapes.small))
                            },
                            trailingIcon = if (entry.color == color) {
                                { Icon(painterResource(R.drawable.ic_book_checked), null) }
                            } else null,
                            onClick = { expanded = false; onColor(entry, color) },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(if (record.note != null) R.string.highlight_edit_note else R.string.highlight_add_note)) },
                    onClick = { expanded = false; onEditNote(entry) },
                )
                if (record.note != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.highlight_remove_note)) },
                        onClick = { expanded = false; onRemoveNote(entry) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.highlight_remove_full)) },
                    onClick = { expanded = false; onRemoveHighlight(entry) },
                )
            }
        }
    }
}

@Composable
internal fun ReaderBookmarkFeedback(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            actionContentColor = colors.onSurface,
            dismissActionContentColor = colors.onSurface,
        )
    }
}
