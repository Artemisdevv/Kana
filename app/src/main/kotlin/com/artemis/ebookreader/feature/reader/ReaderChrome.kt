package com.artemis.ebookreader.feature.reader

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.ReaderPalette
import com.artemis.ebookreader.publication.contentsWithDepth
import com.artemis.ebookreader.ui.readingPercentage
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.theme.EBookReaderTheme
import org.readium.r2.shared.publication.Link

@Composable
internal fun ReaderColors(palette: ReaderPalette, content: @Composable () -> Unit) {
    val background = Color(palette.background)
    val text = Color(palette.text)
    EBookReaderTheme {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = text,
                onPrimary = background,
                surface = background,
                onSurface = text,
                surfaceVariant = background,
                onSurfaceVariant = text,
                surfaceContainerHigh = background,
                surfaceContainerHighest = lerp(background, text, 0.10f),
                background = background,
                onBackground = text,
            ),
            content = content,
        )
    }
}

@Composable
internal fun ReaderTopControls(
    visible: Boolean,
    title: String,
    chapterTitle: String?,
    bookmarked: Boolean,
    canToggleBookmark: Boolean,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
    onHidden: () -> Unit,
) {
    FloatingReaderPanel(visible, slideDirection = -1, onHidden) {
        Row(Modifier.padding(top = 4.dp, end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = visible, colors = readerIconButtonColors()) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.navigate_back))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!chapterTitle.isNullOrBlank() && chapterTitle != title) {
                    Text(chapterTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconToggleButton(
                checked = bookmarked,
                onCheckedChange = { onToggleBookmark() },
                enabled = visible && canToggleBookmark,
            ) {
                Icon(
                    painterResource(if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline),
                    stringResource(if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add),
                )
            }
        }
    }
}

@Composable
internal fun ReaderBottomControls(
    visible: Boolean,
    progression: Double?,
    canShowChapters: Boolean,
    canShowBookmarks: Boolean,
    onShowChapters: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowAppearance: () -> Unit,
    onShowSettings: () -> Unit,
    onHidden: () -> Unit,
) {
    FloatingReaderPanel(visible, slideDirection = 1, onHidden) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val progress = progression?.takeIf { it in 0.0..1.0 }
                Text(
                    progress?.let { readingPercentage(it) } ?: stringResource(R.string.progress_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
                LinearProgressIndicator(
                    progress = { progress?.toFloat() ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val touchTarget = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                if (canShowChapters) {
                    IconButton(onClick = onShowChapters, modifier = touchTarget, enabled = visible, colors = readerIconButtonColors()) {
                        Icon(painterResource(R.drawable.ic_chapters), stringResource(R.string.chapters))
                    }
                }
                IconButton(
                    onClick = onShowBookmarks, modifier = touchTarget,
                    enabled = visible && canShowBookmarks, colors = readerIconButtonColors(),
                ) {
                    Icon(painterResource(R.drawable.ic_bookmarks), stringResource(R.string.saved_passages))
                }
                IconButton(onClick = onShowAppearance, modifier = touchTarget, enabled = visible, colors = readerIconButtonColors()) {
                    Icon(painterResource(R.drawable.ic_contrast), stringResource(R.string.appearance))
                }
                IconButton(onClick = onShowSettings, modifier = touchTarget, enabled = visible, colors = readerIconButtonColors()) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.reader_settings))
                }
            }
        }
    }
}

@Composable
private fun readerIconButtonColors() = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current)

@Composable
private fun FloatingReaderPanel(visible: Boolean, slideDirection: Int, onHidden: () -> Unit, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    val hidden = !visible && state.isIdle && !state.currentState
    val onHiddenNow = rememberUpdatedState(onHidden)
    LaunchedEffect(visible) { state.targetState = visible }
    LaunchedEffect(hidden) { if (hidden) onHiddenNow.value() }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(Motion.Panel, easing = Motion.enter)) +
            slideInVertically(tween(Motion.Panel, easing = Motion.enter)) { slideDirection * it / 5 },
        exit = fadeOut(tween(Motion.Feedback, easing = Motion.exit)) +
            slideOutVertically(tween(Motion.Feedback, easing = Motion.exit)) { slideDirection * it / 5 },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 2.dp,
            content = content,
        )
    }
}

@Composable
internal fun ReaderChapters(
    chapters: List<Link>,
    activeChapter: Link?,
    onClose: () -> Unit,
    onChapter: (Link) -> Unit,
) {
    val entries = remember(chapters) { chapters.contentsWithDepth() }
    val selectedIndex = entries.indexOfFirst { it.first === activeChapter }
    val openingIndex = (selectedIndex - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = openingIndex)
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val layout = listState.layoutInfo
            val selected = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            if (selected == null || selected.offset < layout.viewportStartOffset ||
                selected.offset + selected.size > layout.viewportEndOffset
            ) listState.scrollToItem(selectedIndex)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        title = { ReaderPopupTitle(R.string.chapters) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
                items(entries) { (link, depth) ->
                    val selected = link === activeChapter
                    val background = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else Color.Transparent
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(background)
                            .selectable(selected, role = Role.Button) { onChapter(link) }
                            .padding(start = (12 + depth.coerceAtMost(8) * 10).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = link.title?.takeIf(String::isNotBlank) ?: link.url().toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected || depth == 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun ReaderPopupTitle(@StringRes title: Int) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        window?.setWindowAnimations(R.style.Animation_EBookReader_ChapterDialog)
        onDispose {}
    }
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ReaderMessage(@StringRes message: Int) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            if (message == R.string.loading_book) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = stringResource(message),
                    modifier = Modifier.padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
