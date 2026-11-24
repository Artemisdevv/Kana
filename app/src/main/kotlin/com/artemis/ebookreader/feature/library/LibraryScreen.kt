package com.artemis.ebookreader.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.data.library.matchesSearch
import com.artemis.ebookreader.data.preferences.LibraryLayout
import com.artemis.ebookreader.data.preferences.LibraryViewMode
import com.artemis.ebookreader.ui.BookCover
import com.artemis.ebookreader.ui.BookRow
import com.artemis.ebookreader.ui.BookSelectionMark
import com.artemis.ebookreader.ui.libraryBookGesture
import com.artemis.ebookreader.ui.EmptyScreen
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.transitionInput

@Composable
internal fun LibraryScreen(
    library: LibraryViewModel,
    layout: LibraryLayout,
    bottomPadding: Dp,
    wide: Boolean = false,
    searchQuery: String? = null,
    header: @Composable (String?, LibrarySelection?, Set<String>) -> Unit = { _, _, _ -> },
    onBookAction: (ShelfBook, LibraryBookAction) -> Unit = { _, _ -> },
    onOpen: (String) -> Unit,
) {
    val books by library.sortedBooks.collectAsStateWithLifecycle()
    val selection by library.selection.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var lastMode by rememberSaveable { mutableStateOf(layout.mode) }
    LaunchedEffect(layout.mode) {
        if (layout.mode != lastMode) {
            if (layout.mode == LibraryViewMode.Grid) {
                gridState.requestScrollToItem(listState.firstVisibleItemIndex)
            } else {
                listState.requestScrollToItem(gridState.firstVisibleItemIndex)
            }
            lastMode = layout.mode
        }
    }
    val term = searchQuery.orEmpty().trim()
    val filteredBooks = remember(books, term) {
        if (term.isEmpty()) books else books?.filter { it.shelf.book.matchesSearch(term) }
    }
    val selectableIds = remember(filteredBooks) {
        filteredBooks.orEmpty().filter { it.shelf.available }.mapTo(mutableSetOf()) { it.shelf.book.bookId }
    }
    val visibleSelection = remember(selection, selectableIds) { selection?.visible(selectableIds) }
    LaunchedEffect(selectableIds, filteredBooks != null, selection?.session) {
        if (filteredBooks != null) library.restrictSelection(selectableIds)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val total = books?.size
            val count = when {
                total == null || filteredBooks == null -> null
                searchQuery == null -> stringResource(R.string.library_total_count, total)
                else -> stringResource(R.string.library_filtered_count, filteredBooks.size, total)
            }
            header(count, visibleSelection, selectableIds)
            if (total == null || filteredBooks == null) return@Column

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = layout.mode,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { Motion.fadeThrough().using(null) },
                        label = "Library layout",
                    ) { mode ->
                        Box(
                            Modifier.fillMaxSize().transitionInput(mode == layout.mode),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (mode == LibraryViewMode.Grid) {
                                val spacing = if (layout.columns < 5) 10.dp else 6.dp
                                LazyVerticalGrid(
                                    columns = if (wide) GridCells.Adaptive(124.dp) else GridCells.Fixed(layout.columns),
                                    modifier = Modifier.widthIn(max = if (wide) 1280.dp else Dp.Unspecified),
                                    state = gridState,
                                    contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = bottomPadding + 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else spacing),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(filteredBooks, key = { it.shelf.book.bookId }) { item ->
                                        val entry = item.shelf
                                        val book = entry.book
                                        val selected = visibleSelection?.ids?.contains(book.bookId)
                                        val open = {
                                            if (selected == null) onOpen(book.bookId)
                                            else library.toggleSelection(book.bookId, selectableIds)
                                        }
                                        BookActionAnchor(
                                            entry, onBookAction,
                                            Modifier.animateItem(Motion.standard(Motion.Panel), Motion.placement(), Motion.standard(Motion.Panel)),
                                            selectionMode = selected != null,
                                        ) { onLongClick ->
                                            val outline = if (selected == true) {
                                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                                            } else Modifier
                                            Column(
                                                modifier = Modifier.fillMaxWidth().then(outline)
                                                    .libraryBookGesture(selected, entry.available, open, onLongClick, Role.Button),
                                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                BookCover(
                                                    library.cover(book.assetSha256),
                                                    entry.available,
                                                    Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                                )
                                                Box(Modifier.fillMaxWidth()) {
                                                    Text(
                                                        item.displayName ?: stringResource(R.string.untitled_book),
                                                        modifier = Modifier.fillMaxWidth().padding(
                                                            end = if (selected != null && entry.available) 22.dp else 0.dp,
                                                            bottom = 2.dp,
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        minLines = 2,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (selected != null && entry.available) {
                                                        BookSelectionMark(selected, Modifier.align(Alignment.TopEnd))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.widthIn(max = if (wide) 760.dp else Dp.Unspecified),
                                    state = listState,
                                    contentPadding = PaddingValues(bottom = bottomPadding),
                                ) {
                                    items(filteredBooks, key = { it.shelf.book.bookId }) { item ->
                                        val book = item.shelf
                                        val selected = visibleSelection?.ids?.contains(book.book.bookId)
                                        BookActionAnchor(
                                            book, onBookAction,
                                            modifier = Modifier.animateItem(
                                                Motion.standard(Motion.Panel), Motion.placement(), Motion.standard(Motion.Panel),
                                            ),
                                            selectionMode = selected != null,
                                        ) { onLongClick ->
                                            BookRow(
                                                book = book,
                                                name = item.displayName ?: stringResource(R.string.untitled_book),
                                                author = item.author,
                                                cover = library.cover(book.book.assetSha256),
                                                onOpen = {
                                                    if (selected == null) onOpen(book.book.bookId)
                                                    else library.toggleSelection(book.book.bookId, selectableIds)
                                                },
                                                onLongClick = onLongClick,
                                                selected = selected,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = filteredBooks.isEmpty(),
                        enter = fadeIn(Motion.standard(Motion.Panel)),
                        exit = fadeOut(Motion.standard(Motion.Feedback)),
                    ) {
                        if (total == 0) {
                            EmptyScreen(R.drawable.ic_book, R.string.library_empty_title, R.string.library_empty_body)
                        } else {
                            EmptyScreen(R.drawable.ic_search, R.string.library_no_search_results, R.string.library_no_search_results_hint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibrarySearchBar(
    query: String?,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val open = query != null
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(Motion.standard(Motion.Panel), expandFrom = Alignment.Top) +
            fadeIn(Motion.standard(Motion.Panel)),
        exit = shrinkVertically(Motion.standard(Motion.Panel), shrinkTowards = Alignment.Top) +
            fadeOut(Motion.standard(Motion.Feedback)),
    ) {
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val colors = MaterialTheme.colorScheme
        val label = stringResource(R.string.library_search)
        LaunchedEffect(requestFocus, open) {
            if (requestFocus && open) {
                focusRequester.requestFocus()
                keyboard?.show()
                onFocusHandled()
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            TextField(
                value = query.orEmpty(),
                onValueChange = onQueryChange,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .transitionInput(open).focusRequester(focusRequester).semantics { contentDescription = label },
                placeholder = { Text(label) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (!query.isNullOrEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(painterResource(R.drawable.ic_close_search), stringResource(R.string.library_clear_search))
                        }
                    }
                },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainerHigh,
                    unfocusedContainerColor = colors.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
            )
        }
    }
}

