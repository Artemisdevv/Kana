package com.artemis.ebookreader.feature.history

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.displayName
import com.artemis.ebookreader.data.library.matchesSearch
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.ui.BookCover
import com.artemis.ebookreader.ui.EmptyScreen
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.readingPercentage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

@Composable
internal fun HistoryScreen(
    library: LibraryViewModel,
    search: String?,
    bottomPadding: Dp,
    wide: Boolean,
    onSearchChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val loadedHistory by library.history.collectAsStateWithLifecycle()
    val organization by library.organization.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val listState = rememberLazyListState()

    Column(Modifier.widthIn(max = if (wide) 760.dp else Dp.Unspecified).fillMaxSize()) {
        if (search != null) HistorySearchField(search, onSearchChange)
        val history = loadedHistory ?: return@Column
        val nameMode = organization?.displayName ?: return@Column
        val groups = remember(history, search, zone) { groupHistory(history, search.orEmpty(), zone) }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
                for ((day, books) in groups) {
                    item("date-$day") {
                        Text(
                            dateHeading(day, today, zone),
                            Modifier.animateItem(Motion.standard(Motion.Panel), Motion.placement(), Motion.standard(Motion.Panel))
                                .fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(books, key = { it.book.bookId }) { book ->
                        HistoryRow(
                            book = book,
                            name = book.book.displayName(nameMode) ?: stringResource(R.string.untitled_book),
                            cover = library.cover(book.book.assetSha256),
                            onOpen = { onOpen(book.book.bookId) },
                            onRemove = { onRemove(book.book.bookId) },
                            modifier = Modifier.animateItem(
                                Motion.standard(Motion.Panel), Motion.placement(), Motion.standard(Motion.Panel),
                            ),
                        )
                    }
                }
            }
            HistoryEmpty(visible = groups.isEmpty(), hasHistory = history.isNotEmpty())
        }
    }
}

@Composable
private fun HistoryEmpty(visible: Boolean, hasHistory: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.standard(Motion.Panel)),
        exit = fadeOut(Motion.standard(Motion.Feedback)),
    ) {
        if (hasHistory) {
            EmptyScreen(R.drawable.ic_search, R.string.history_no_matches, R.string.history_search_hint)
        } else {
            EmptyScreen(R.drawable.ic_history, R.string.history_empty_title, R.string.history_empty_body)
        }
    }
}

internal fun groupHistory(history: List<ShelfBook>, search: String, zone: ZoneId): Map<LocalDate, List<ShelfBook>> {
    val term = search.trim()
    return history.asSequence()
        .filter { it.book.matchesSearch(term) }
        .mapNotNull { book ->
            book.lastReadAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() to book }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
}

@Composable
private fun dateHeading(day: LocalDate, today: LocalDate, zone: ZoneId): String {
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when (daysAgo) {
        0L -> stringResource(R.string.history_today)
        1L -> stringResource(R.string.history_yesterday)
        in 2L..6L -> pluralStringResource(R.plurals.history_days_ago, daysAgo.toInt(), daysAgo.toInt())
        else -> DateFormat.getMediumDateFormat(LocalContext.current)
            .format(Date(day.atStartOfDay(zone).toInstant().toEpochMilli()))
    }
}

@Composable
private fun HistorySearchField(search: String, onSearchChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = search,
        onValueChange = onSearchChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus),
        label = { Text(stringResource(R.string.search_history)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun HistoryRow(book: ShelfBook, name: String, cover: File, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(cover, book.available, Modifier.size(width = 48.dp, height = 68.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = book.progression?.takeIf { it in 0.0..1.0 }?.let { readingPercentage(it) }
                val time = book.lastReadAt?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
                val section = book.section?.takeIf { label ->
                    label.isNotBlank() && (label.any(Char::isWhitespace) ||
                        label.substringAfterLast('.').lowercase() !in setOf("xhtml", "html", "htm"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (section != null) {
                        HistoryDetail(section, Modifier.weight(1f, fill = false))
                    }
                    val readingDetails = listOfNotNull(progress, time).joinToString(" · ")
                    if (readingDetails.isNotEmpty()) {
                        HistoryDetail(if (section == null) readingDetails else "· $readingDetails")
                    }
                }
                if (!book.available) HistoryDetail(stringResource(R.string.file_missing_label))
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(R.drawable.ic_remove_history),
                contentDescription = stringResource(R.string.remove_from_history, name),
            )
        }
    }
}

@Composable
private fun HistoryDetail(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
