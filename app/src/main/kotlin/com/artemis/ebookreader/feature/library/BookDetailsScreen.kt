package com.artemis.ebookreader.feature.library

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.displayName
import com.artemis.ebookreader.data.storage.currentFilename
import com.artemis.ebookreader.feature.settings.SettingsColumn
import com.artemis.ebookreader.feature.settings.SettingsGroup
import com.artemis.ebookreader.ui.BookCover
import com.artemis.ebookreader.ui.EmptyScreen
import com.artemis.ebookreader.ui.readingPercentage
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.map

@Composable
internal fun BookDetailsScreen(bookId: String, library: LibraryViewModel, bottomPadding: Dp, onOpen: (String) -> Unit) {
    val details by remember(bookId, library) { library.details(bookId).map { Result.success(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val organization by library.organization.collectAsStateWithLifecycle()
    val loaded = details ?: return
    val entry = loaded.getOrNull()
    if (entry == null) {
        EmptyScreen(R.drawable.ic_book, R.string.library_details_unavailable, R.string.library_details_unavailable_body)
        return
    }
    val nameMode = organization?.displayName ?: return
    val shelf = entry.shelf
    val book = shelf.book
    val name = book.displayName(nameMode) ?: stringResource(R.string.untitled_book)
    val filename = currentFilename(book.sourceName, shelf.format)
    val format = shelf.format.uppercase(Locale.ROOT)
    val locale = LocalConfiguration.current.locales[0]
    val language = remember(book.language, locale) {
        book.language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }?.let { tag ->
            Locale.forLanguageTag(tag).getDisplayName(locale).ifBlank { tag }
        }
    }
    val progress = shelf.progression?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?: if (shelf.lastReadAt == null) 0.0 else null
    val unavailable = stringResource(R.string.library_metadata_unavailable)
    val percentage = if (progress == null) unavailable else readingPercentage(progress)
    val context = LocalContext.current

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        SettingsColumn(bottomPadding, Modifier.widthIn(max = 720.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BookCover(library.cover(book.assetSha256), shelf.available, Modifier.size(width = 92.dp, height = 138.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(book.authorLine.ifBlank { stringResource(R.string.unknown_author) }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(format, language).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(percentage, style = MaterialTheme.typography.bodySmall)
                        if (!shelf.available) Text(stringResource(R.string.file_missing_label), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { onOpen(bookId) }, enabled = shelf.available) {
                            Text(stringResource(if (shelf.lastReadAt == null) R.string.library_start_reading else R.string.continue_book))
                        }
                    }
                }
            }
            SettingsGroup(R.string.library_book_information) {
                DetailValue(R.string.library_embedded_title, book.title.trim().ifBlank { unavailable })
                DetailValue(R.string.library_sort_author, book.authorLine.ifBlank { stringResource(R.string.unknown_author) })
                language?.let { DetailValue(R.string.library_language, it) }
                book.publisher?.takeUnless(String::isBlank)?.let { DetailValue(R.string.library_publisher, it) }
                book.identifier?.takeUnless(String::isBlank)?.let { DetailValue(R.string.library_identifier, it) }
            }
            SettingsGroup(R.string.library_file_information) {
                DetailValue(R.string.library_filename, filename ?: unavailable)
                DetailValue(R.string.library_file_format, format)
                shelf.fileSize?.takeIf { it > 0 }?.let {
                    DetailValue(R.string.library_sort_file_size, Formatter.formatFileSize(context, it))
                }
                book.importedAt.takeIf { it > 0 }?.let {
                    DetailValue(R.string.library_sort_date_imported, DateFormat.getMediumDateFormat(context).format(Date(it)))
                }
            }
            SettingsGroup(R.string.library_reading_information) {
                entry.chapter?.let { DetailValue(R.string.library_current_chapter, it) }
                DetailValue(R.string.library_reading_progress, percentage)
                val lastRead = shelf.lastReadAt?.let {
                    val date = Date(it)
                    stringResource(
                        R.string.library_date_time,
                        DateFormat.getMediumDateFormat(context).format(date),
                        DateFormat.getTimeFormat(context).format(date),
                    )
                } ?: stringResource(R.string.library_never_read)
                DetailValue(R.string.library_sort_last_read, lastRead)
                DetailValue(R.string.library_details_bookmarks, java.text.NumberFormat.getIntegerInstance(locale).format(entry.bookmarkCount))
            }
        }
    }
}

@Composable
private fun DetailValue(@StringRes label: Int, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
