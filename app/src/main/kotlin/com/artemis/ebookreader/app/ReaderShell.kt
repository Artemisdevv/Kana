package com.artemis.ebookreader.app

import android.app.Activity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.ReaderAppearanceSettings
import com.artemis.ebookreader.data.preferences.ApplicationThemeSettings
import com.artemis.ebookreader.feature.history.HistoryScreen
import com.artemis.ebookreader.feature.home.HomeScreen
import com.artemis.ebookreader.feature.library.LibraryLayoutSheet
import com.artemis.ebookreader.feature.library.BookDetailsScreen
import com.artemis.ebookreader.feature.library.LibraryBookAction
import com.artemis.ebookreader.feature.library.LibraryBookDialog
import com.artemis.ebookreader.feature.library.LibraryBulkFeedback
import com.artemis.ebookreader.feature.library.LibraryBulkStatus
import com.artemis.ebookreader.feature.library.LibrarySelectionToolbar
import com.artemis.ebookreader.feature.library.createExportDocument
import com.artemis.ebookreader.feature.library.LibraryScreen
import com.artemis.ebookreader.feature.library.LibrarySearchBar
import com.artemis.ebookreader.feature.library.LibrarySortButton
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.feature.settings.SettingsScreen
import com.artemis.ebookreader.feature.settings.SettingsPage
import com.artemis.ebookreader.feature.settings.SettingsSplitPane
import com.artemis.ebookreader.ui.EmptyScreen
import com.artemis.ebookreader.ui.Motion
import com.artemis.ebookreader.ui.transitionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PrimaryDestination(
    @param:StringRes @get:StringRes val title: Int,
    @param:DrawableRes @get:DrawableRes val icon: Int,
) {
    Home(R.string.home, R.drawable.ic_home),
    Library(R.string.library, R.drawable.ic_book),
    History(R.string.history, R.drawable.ic_history),
    Settings(R.string.settings, R.drawable.ic_settings),
}

private val RailWidth = 80.dp
private val RailMargin = 12.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ReaderShell(
    library: LibraryViewModel,
    readerAppearance: ReaderAppearanceSettings,
    appAppearance: ApplicationThemeSettings,
    onOpen: (String) -> Unit,
) {
    var selectedDestination by rememberSaveable { mutableStateOf(PrimaryDestination.Home) }
    var settingsReturnDestination by rememberSaveable { mutableStateOf(PrimaryDestination.Home) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val pageMotion = remember { SeekableTransitionState(settingsPage) }
    val pageTransition = rememberTransition(pageMotion, label = "Settings navigation")
    LaunchedEffect(settingsPage) { pageMotion.animateTo(settingsPage) }
    var historySearch by rememberSaveable { mutableStateOf<String?>(null) }
    var librarySearch by rememberSaveable { mutableStateOf<String?>(null) }
    var requestLibrarySearchFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissLibrarySearchInput() {
        requestLibrarySearchFocus = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun closeLibrarySearch() {
        dismissLibrarySearchInput()
        librarySearch = null
    }
    var showLibraryLayout by rememberSaveable { mutableStateOf(false) }
    var detailsBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailsMotion = remember { SeekableTransitionState(detailsBookId) }
    val detailsTransition = rememberTransition(detailsMotion, label = "Book details navigation")
    LaunchedEffect(detailsBookId) { detailsMotion.animateTo(detailsBookId) }
    var bookDialogId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookDialogAction by rememberSaveable { mutableStateOf<LibraryBookAction?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val libraryLayout by library.layout.collectAsStateWithLifecycle()
    val screenState = rememberSaveableStateHolder()
    val importing by library.importing.collectAsStateWithLifecycle()
    val message by library.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val wide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val short = !adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val paneDirective = remember(adaptiveInfo) { calculatePaneScaffoldDirective(adaptiveInfo) }
    val selectDestination: (PrimaryDestination) -> Unit = { destination ->
        if (selectedDestination == PrimaryDestination.Library && destination != PrimaryDestination.Library && librarySearch != null) {
            dismissLibrarySearchInput()
        }
        if (selectedDestination == PrimaryDestination.Library && destination != PrimaryDestination.Library) {
            detailsBookId = null
            library.clearSelection()
        }
        if (destination == PrimaryDestination.Settings && selectedDestination != PrimaryDestination.Settings) {
            settingsReturnDestination = selectedDestination
            settingsPage = null
        }
        selectedDestination = destination
    }
    val historyRemovedMessage = stringResource(R.string.history_removed)
    val historyUpdateFailedMessage = stringResource(R.string.history_update_failed)
    val undoLabel = stringResource(R.string.undo)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(library::importBook)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bookId = pendingExport
        pendingExport = null
        if (bookId != null && result.resultCode == Activity.RESULT_OK) {
            val destination = result.data?.data
            if (destination != null) library.exportBook(bookId, destination)
            else library.reportMessage(R.string.library_export_failed)
        }
    }
    val defaultExportName = stringResource(R.string.untitled_book)
    val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { directory ->
        library.exportDirectoryPicked(directory, librarySearch, defaultExportName)
    }
    val messageId = message
    if (messageId != null) {
        val text = stringResource(messageId)
        LaunchedEffect(messageId, text) {
            snackbar.showSnackbar(text)
            library.messageShown(messageId)
        }
    }
    LibraryBulkFeedback(library, snackbar)

    PredictiveBackHandler(enabled = selectedDestination != PrimaryDestination.Home) { events ->
        val previewPage = settingsPage.takeIf {
            selectedDestination == PrimaryDestination.Settings && pageTransition.currentState == it
        }
        val previewBook = detailsBookId != null && selectedDestination == PrimaryDestination.Library &&
            detailsTransition.currentState == detailsBookId
        try {
            events.collect {
                if (previewPage != null) pageMotion.seekTo(it.progress, targetState = null)
                if (previewBook) detailsMotion.seekTo(it.progress, targetState = null)
            }
            when {
                selectedDestination == PrimaryDestination.Settings && settingsPage != null -> settingsPage = null
                selectedDestination == PrimaryDestination.History && historySearch != null -> historySearch = null
                selectedDestination == PrimaryDestination.Library && detailsBookId != null -> detailsBookId = null
                selectedDestination == PrimaryDestination.Library && library.selection.value != null -> library.clearSelection()
                selectedDestination == PrimaryDestination.Library && librarySearch != null -> closeLibrarySearch()
                selectedDestination == PrimaryDestination.Settings -> selectedDestination = settingsReturnDestination
                else -> selectedDestination = PrimaryDestination.Home
            }
        } catch (_: CancellationException) {
            if (previewPage != null) withContext(NonCancellable) { pageMotion.animateTo(settingsPage) }
            if (previewBook) withContext(NonCancellable) { detailsMotion.animateTo(detailsBookId) }
        }
    }

    val screenHeader: @Composable (PrimaryDestination, SettingsPage?, String?) -> Unit = { destination, page, count ->
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = {
                val title = stringResource(page?.title ?: destination.title)
                if (count == null) {
                    Text(title)
                } else {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            title,
                            Modifier.alignByBaseline().weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            count,
                            Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                if (page != null) {
                    IconButton(onClick = { settingsPage = null }) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.navigate_back))
                    }
                }
            },
            actions = {
                if (destination == PrimaryDestination.Library) {
                    val searching = librarySearch != null
                    IconButton(onClick = {
                        if (librarySearch == null) {
                            librarySearch = ""
                            requestLibrarySearchFocus = true
                        } else {
                            closeLibrarySearch()
                        }
                    }) {
                        Icon(
                            painterResource(if (searching) R.drawable.ic_close_search else R.drawable.ic_search),
                            stringResource(if (searching) R.string.library_close_search else R.string.library_search),
                        )
                    }
                    LibrarySortButton(library)
                    IconButton(onClick = { showLibraryLayout = true }) {
                        Icon(painterResource(R.drawable.ic_grid), stringResource(R.string.library_layout))
                    }
                    if (importing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.import_book))
                        }
                    }
                }
                if (destination == PrimaryDestination.History) {
                    val searching = historySearch != null
                    IconButton(onClick = { historySearch = if (searching) null else "" }) {
                        Icon(
                            painterResource(if (searching) R.drawable.ic_close_search else R.drawable.ic_search),
                            stringResource(if (searching) R.string.close_history_search else R.string.search_history),
                        )
                    }
                }
            },
        )
    }

    val railSpace = RailWidth + RailMargin * 2
    val safeInsets = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val safeHorizontal = with(density) {
        (safeInsets.getLeft(this, layoutDirection) + safeInsets.getRight(this, layoutDirection)).toDp()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Even an Expanded window may be too narrow after reserving the rail and display insets.
        val splitSettings = wide && paneDirective.maxHorizontalPartitions >= 2 &&
            maxWidth - railSpace - safeHorizontal >= 800.dp
        Scaffold(
            modifier = if (wide) {
                Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Start)).padding(start = railSpace)
            } else {
                Modifier
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) NavigationBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .shadow(3.dp, CircleShape),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    PrimaryDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selectedDestination,
                            onClick = { selectDestination(destination) },
                            icon = {
                                Icon(painterResource(destination.icon), contentDescription = null)
                            },
                            label = { Text(stringResource(destination.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { contentPadding ->
            val framePadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection),
            )
            val bottomPadding = contentPadding.calculateBottomPadding()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(framePadding)
                    .consumeWindowInsets(framePadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = selectedDestination,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    transitionSpec = { Motion.push(forward = targetState.ordinal > initialState.ordinal).using(null) },
                    label = "Main navigation",
                ) { destination ->
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                            .transitionInput(destination == selectedDestination),
                    ) {
                        if (destination == PrimaryDestination.Settings) {
                            val showPage: @Composable (SettingsPage?, SettingsPage?) -> Unit = { page, selection ->
                                screenState.SaveableStateProvider("settings-${page?.name}") {
                                    SettingsScreen(
                                        page = page,
                                        readerAppearance = readerAppearance,
                                        appAppearance = appAppearance,
                                        library = library,
                                        libraryLayout = libraryLayout,
                                        scope = scope,
                                        bottomPadding = bottomPadding,
                                        onOpenPage = { settingsPage = it },
                                        importing = importing,
                                        onImportBook = { picker.launch(arrayOf("*/*")) },
                                        selectedPage = selection,
                                        wide = wide,
                                    )
                                }
                            }
                            val details: @Composable (Boolean) -> Unit = { split ->
                                pageTransition.AnimatedContent(
                                    modifier = Modifier.fillMaxSize().clipToBounds(),
                                    transitionSpec = { Motion.push(forward = targetState != null).using(null) },
                                ) { page ->
                                    val active = destination == selectedDestination && page == settingsPage
                                    Column(
                                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                            .transitionInput(active),
                                    ) {
                                        if (page != null || split) screenHeader(destination, page, null)
                                        if (page == null && split) {
                                            EmptyScreen(
                                                R.drawable.ic_settings,
                                                R.string.settings_choose_category,
                                                R.string.settings_choose_category_message,
                                            )
                                        } else {
                                            Box(
                                                Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.TopCenter,
                                            ) {
                                                Box(Modifier.widthIn(max = if (wide && !split) 620.dp else Dp.Unspecified)) {
                                                    showPage(page, null)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (splitSettings) {
                                SettingsSplitPane(
                                    page = settingsPage,
                                    directive = paneDirective,
                                    list = { showPage(null, settingsPage) },
                                    detail = { details(true) },
                                )
                            } else {
                                details(false)
                            }
                        } else {
                            if (destination != PrimaryDestination.Library) screenHeader(destination, null, null)
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                screenState.SaveableStateProvider(destination.name) {
                                    when (destination) {
                                        PrimaryDestination.Home -> HomeScreen(library, bottomPadding, wide, onOpen)
                                        PrimaryDestination.Library -> detailsTransition.AnimatedContent(
                                            modifier = Modifier.fillMaxSize().clipToBounds(),
                                            transitionSpec = { Motion.push(forward = targetState != null).using(null) },
                                        ) { bookId ->
                                            val active = destination == selectedDestination && bookId == detailsBookId
                                            Column(
                                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                                    .transitionInput(active),
                                            ) {
                                                if (bookId == null) {
                                                    screenState.SaveableStateProvider("library-books") {
                                                        LibraryScreen(
                                                            library = library,
                                                            layout = libraryLayout,
                                                            bottomPadding = bottomPadding,
                                                            wide = wide,
                                                            searchQuery = librarySearch,
                                                            header = { count, selection, selectableIds ->
                                                                if (selection == null) {
                                                                    screenHeader(destination, null, count)
                                                                } else {
                                                                    LibrarySelectionToolbar(library, selection, selectableIds, librarySearch) {
                                                                        if (library.requestBulkExport(selection, librarySearch)) {
                                                                            dismissLibrarySearchInput()
                                                                            if (runCatching { exportDirectoryPicker.launch(null) }.isFailure) {
                                                                                library.exportDirectoryPicked(null, librarySearch, defaultExportName)
                                                                                library.reportMessage(R.string.library_export_failed)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                LibrarySearchBar(
                                                                    query = librarySearch,
                                                                    requestFocus = requestLibrarySearchFocus && active,
                                                                    onFocusHandled = { requestLibrarySearchFocus = false },
                                                                    onQueryChange = { if (librarySearch != null) librarySearch = it },
                                                                )
                                                                LibraryBulkStatus(library)
                                                            },
                                                            onBookAction = { entry, action ->
                                                                val id = entry.book.bookId
                                                                when (action) {
                                                                    LibraryBookAction.Select -> library.startSelection(id)
                                                                    LibraryBookAction.Details -> {
                                                                        if (librarySearch != null) dismissLibrarySearchInput()
                                                                        detailsBookId = id
                                                                    }
                                                                    LibraryBookAction.Export -> if (pendingExport == null) {
                                                                        val request = entry.createExportDocument(defaultExportName)
                                                                        if (request == null) {
                                                                            library.reportMessage(R.string.book_unsupported)
                                                                        } else {
                                                                            pendingExport = id
                                                                            if (runCatching { exporter.launch(request) }.isFailure) {
                                                                                pendingExport = null
                                                                                library.reportMessage(R.string.library_export_failed)
                                                                            }
                                                                        }
                                                                    }
                                                                    else -> {
                                                                        bookDialogId = id
                                                                        bookDialogAction = action
                                                                    }
                                                                }
                                                            },
                                                            onOpen = { id ->
                                                                if (librarySearch != null) dismissLibrarySearchInput()
                                                                onOpen(id)
                                                            },
                                                        )
                                                    }
                                                } else {
                                                    TopAppBar(
                                                        windowInsets = WindowInsets(0, 0, 0, 0),
                                                        title = { Text(stringResource(R.string.library_book_details)) },
                                                        navigationIcon = {
                                                            IconButton(onClick = { detailsBookId = null }) {
                                                                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.navigate_back))
                                                            }
                                                        },
                                                    )
                                                    screenState.SaveableStateProvider("book-details-$bookId") {
                                                        BookDetailsScreen(bookId, library, bottomPadding, onOpen)
                                                    }
                                                }
                                            }
                                        }
                                        PrimaryDestination.History -> HistoryScreen(
                                            library = library,
                                            search = historySearch,
                                            bottomPadding = bottomPadding,
                                            wide = wide,
                                            onSearchChange = { historySearch = it },
                                            onOpen = onOpen,
                                            onRemove = { bookId ->
                                                scope.launch {
                                                    try {
                                                        val reset = library.resetReading(bookId) ?: return@launch
                                                        val result = snackbar.showSnackbar(
                                                            message = historyRemovedMessage,
                                                            actionLabel = undoLabel,
                                                            withDismissAction = true,
                                                            duration = SnackbarDuration.Long,
                                                        )
                                                        if (result == SnackbarResult.ActionPerformed) library.undoReset(reset)
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        snackbar.showSnackbar(historyUpdateFailedMessage)
                                                    }
                                                }
                                            },
                                        )
                                        PrimaryDestination.Settings -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (wide) {
            FloatingNavigationRail(
                selected = selectedDestination,
                onSelect = selectDestination,
                short = short,
                modifier = Modifier.align(Alignment.CenterStart)
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                    .padding(horizontal = RailMargin, vertical = 8.dp),
            )
        }
    }
    if (selectedDestination == PrimaryDestination.Library && detailsBookId == null && showLibraryLayout) {
        LibraryLayoutSheet(
            layout = libraryLayout,
            onModeChange = library::setViewMode,
            onColumnsChange = library::setGridColumns,
            onDismiss = { showLibraryLayout = false },
            wide = wide,
        )
    }
    val dialogId = bookDialogId
    val dialogAction = bookDialogAction
    if (dialogId != null && dialogAction != null && selectedDestination == PrimaryDestination.Library) {
        val books by library.books.collectAsStateWithLifecycle()
        val book = books?.firstOrNull { it.book.bookId == dialogId }
        if (book != null) {
            LibraryBookDialog(book, dialogAction, library) {
                bookDialogId = null
                bookDialogAction = null
            }
        } else if (books != null) {
            LaunchedEffect(dialogId) {
                bookDialogId = null
                bookDialogAction = null
            }
        }
    }
}

@Composable
private fun FloatingNavigationRail(
    selected: PrimaryDestination,
    onSelect: (PrimaryDestination) -> Unit,
    short: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(RailWidth)
            .shadow(3.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .verticalScroll(rememberScrollState())
            .padding(vertical = if (short) 12.dp else 16.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (short) 0.dp else 4.dp),
    ) {
        PrimaryDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

