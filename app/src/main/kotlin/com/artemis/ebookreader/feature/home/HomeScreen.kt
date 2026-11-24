package com.artemis.ebookreader.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.displayName
import com.artemis.ebookreader.data.preferences.BookDisplayName
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.ui.BookCover
import com.artemis.ebookreader.ui.EmptyScreen
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.lastReadLabel
import com.artemis.ebookreader.ui.readingPercentage
import com.artemis.ebookreader.ui.transitionInput
import java.io.File

@Composable
internal fun HomeScreen(library: LibraryViewModel, bottomPadding: Dp, wide: Boolean, onOpen: (String) -> Unit) {
    val loadedBooks by library.recent.collectAsStateWithLifecycle()
    val organization by library.organization.collectAsStateWithLifecycle()
    val compactScroll = rememberLazyListState()
    val featuredScroll = rememberScrollState()
    val recentScroll = rememberLazyListState()
    val books = loadedBooks ?: return
    val nameMode = organization?.displayName ?: return
    val featured = books.firstOrNull()
    if (featured == null) {
        EmptyScreen(R.drawable.ic_book, R.string.home_empty_title, R.string.home_empty_body)
        return
    }
    val recent = books.drop(1)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (wide && maxWidth >= 640.dp && recent.isNotEmpty()) {
            Row(Modifier.widthIn(max = 1060.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(featuredScroll).padding(bottom = bottomPadding + 16.dp)) {
                    ReadingNow(featured, nameMode, library, onOpen)
                }
                LazyColumn(
                    Modifier.weight(1f),
                    state = recentScroll,
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
                ) {
                    recentBooks(recent, nameMode, library, onOpen)
                }
            }
        } else {
            LazyColumn(
                Modifier.widthIn(max = if (wide) 540.dp else Dp.Unspecified).fillMaxSize(),
                state = compactScroll,
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
            ) {
                item("reading-now") { ReadingNow(featured, nameMode, library, onOpen) }
                if (recent.isNotEmpty()) recentBooks(recent, nameMode, library, onOpen, headingTopPadding = 12.dp)
            }
        }
    }
}

@Composable
private fun ReadingNow(book: ShelfBook, nameMode: BookDisplayName, library: LibraryViewModel, onOpen: (String) -> Unit) {
    SectionHeading(stringResource(R.string.reading_now))
    AnimatedContent(
        targetState = book,
        contentKey = { it.book.bookId },
        transitionSpec = { Motion.fadeThrough().using(null) },
        label = "Featured book",
    ) { featured ->
        Box(Modifier.transitionInput(featured.book.bookId == book.book.bookId)) {
            FeaturedBook(featured, nameMode, library.cover(featured.book.assetSha256)) { onOpen(featured.book.bookId) }
        }
    }
}

private fun LazyListScope.recentBooks(
    recent: List<ShelfBook>,
    nameMode: BookDisplayName,
    library: LibraryViewModel,
    onOpen: (String) -> Unit,
    headingTopPadding: Dp = 0.dp,
) {
    item("recently") { SectionHeading(stringResource(R.string.recently_read), Modifier.padding(top = headingTopPadding)) }
    itemsIndexed(recent, key = { _, book -> book.book.bookId }) { index, book ->
        Column(Modifier.animateItem(Motion.standard(Motion.Panel), Motion.placement(), Motion.standard(Motion.Panel))) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(book.book.bookId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(library.cover(book.book.assetSha256), book.available, Modifier.size(width = 80.dp, height = 120.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BookDetails(book, nameMode, featured = false)
                    BookProgress(book.progression)
                }
            }
            if (index < recent.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun FeaturedBook(book: ShelfBook, nameMode: BookDisplayName, cover: File, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.padding(12.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BookCover(cover, book.available, Modifier.weight(0.39f).aspectRatio(2f / 3f))
            Column(Modifier.weight(0.61f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BookDetails(book, nameMode, featured = true)
                }
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BookProgress(book.progression)
                    Button(onClick = onOpen, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                        Text(stringResource(R.string.continue_book))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetails(book: ShelfBook, nameMode: BookDisplayName, featured: Boolean) {
    Text(
        book.book.displayName(nameMode) ?: stringResource(R.string.untitled_book),
        style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val style = if (featured) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        book.book.authorLine.ifBlank { stringResource(R.string.unknown_author) },
        style = style,
        color = color,
        maxLines = if (featured) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
    book.lastReadAt?.let { Text(lastReadLabel(it), style = style, color = color) }
    if (!book.available) Text(stringResource(R.string.file_missing_label), style = style, color = color)
}

@Composable
private fun BookProgress(progression: Double?) {
    val progress = progression?.takeIf { it in 0.0..1.0 }
    val label = if (progress == null) stringResource(R.string.progress_unavailable) else readingPercentage(progress)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (progress != null) {
        val displayed = animateFloatAsState(progress.toFloat(), Motion.standard(), label = "Reading progress")
        LinearProgressIndicator(
            progress = { displayed.value },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
    }
}
