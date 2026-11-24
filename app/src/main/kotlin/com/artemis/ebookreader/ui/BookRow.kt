package com.artemis.ebookreader.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.publication.BookProblem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BookRow(
    book: ShelfBook,
    name: String,
    author: String,
    cover: File,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val selectionShape = MaterialTheme.shapes.medium
    val decoration = if (selected == true) Modifier.background(colors.secondaryContainer, selectionShape)
    else Modifier
    Row(
        modifier = modifier.fillMaxWidth().then(decoration)
            .libraryBookGesture(selected, book.available, onOpen, onLongClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(cover, book.available, Modifier.size(width = 52.dp, height = 72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Details(author.ifBlank { stringResource(R.string.unknown_author) })
            if (!book.available) Details(stringResource(R.string.file_missing_label))
        }
        if (selected != null && book.available) BookSelectionMark(selected)
    }
}

@Composable
internal fun Modifier.libraryBookGesture(
    selected: Boolean?,
    available: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    role: Role? = null,
): Modifier = if (selected == null) {
    combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        role = role,
        onLongClickLabel = stringResource(R.string.library_book_actions),
        hapticFeedbackEnabled = true,
    )
} else {
    toggleable(value = selected, enabled = available, role = Role.Checkbox) { onClick() }
}

@Composable
internal fun BookSelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colors.primary else colors.surface,
        animationSpec = Motion.standard(Motion.Feedback),
        label = "Book selection",
    )
    Box(
        modifier.size(19.dp).clip(CircleShape)
            .background(fill)
            .border(1.dp, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_book_checked), null, Modifier.size(14.dp), tint = colors.onPrimary)
        }
    }
}

// Cover paths are content-addressed. Keep successful decodes across lazy item disposal.
private val coverCache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

internal fun forgetBookCover(cover: File) {
    coverCache.remove(cover.path)
}

@Composable
internal fun BookCover(cover: File, available: Boolean, modifier: Modifier) {
    val path = cover.path
    var coverImage by remember(path, available) { mutableStateOf(coverCache.get(path)) }
    LaunchedEffect(path, available) {
        if (coverImage == null) {
            coverImage = withContext(Dispatchers.IO) {
                coverCache.get(path) ?: BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { coverCache.put(path, it) }
            }
        }
    }
    Box(
        modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(coverImage, animationSpec = Motion.standard(Motion.Panel), label = "Book cover") { image ->
            if (image == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_book),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun Details(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
internal fun bookProblemMessage(problem: BookProblem): Int = when (problem) {
    BookProblem.Unsupported -> R.string.book_unsupported
    BookProblem.Corrupt -> R.string.book_corrupt
    BookProblem.Protected -> R.string.book_protected
    BookProblem.TooLarge -> R.string.book_too_large
    BookProblem.Unreadable -> R.string.book_unreadable
    BookProblem.Missing -> R.string.book_missing
}
