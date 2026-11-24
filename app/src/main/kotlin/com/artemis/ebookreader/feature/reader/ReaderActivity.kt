package com.artemis.ebookreader.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.RoundedCorner
import android.view.animation.AnimationUtils
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.graphics.Insets
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.artemis.ebookreader.R
import com.artemis.ebookreader.app.EBookReaderApplication
import com.artemis.ebookreader.data.library.displayName
import com.artemis.ebookreader.data.library.Bookmark
import com.artemis.ebookreader.data.library.OpenBook
import com.artemis.ebookreader.data.library.Highlight
import com.artemis.ebookreader.data.library.HighlightColor
import com.artemis.ebookreader.data.library.MaxHighlightLength
import com.artemis.ebookreader.data.library.SavedHighlight
import com.artemis.ebookreader.data.library.ResolvedBookmark
import com.artemis.ebookreader.data.preferences.ReaderAppearance
import com.artemis.ebookreader.data.preferences.ReaderFont
import com.artemis.ebookreader.data.preferences.ReaderLayout
import com.artemis.ebookreader.data.preferences.ReaderTheme
import com.artemis.ebookreader.feature.settings.ReadingPreferenceTab
import com.artemis.ebookreader.feature.settings.ReadingPreferencesSheet
import com.artemis.ebookreader.publication.BookFailure
import com.artemis.ebookreader.publication.SavedLocation
import com.artemis.ebookreader.publication.contentsWithDepth
import com.artemis.ebookreader.publication.matchesBookmarkFallback
import com.artemis.ebookreader.publication.resumeLocator
import com.artemis.ebookreader.ui.bookProblemMessage
import com.artemis.ebookreader.ui.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import kotlin.math.roundToInt
import kotlin.math.abs

private data class SelectionMenu(val session: ReaderSelectionSession, val anchor: ReaderPopupAnchor)
private data class HighlightMenu(val id: String, val anchor: ReaderPopupAnchor)
private data class NoteDraft(val locator: Locator, val chapter: String?, val spineIndex: Int, val existing: SavedHighlight? = null)

@OptIn(ExperimentalReadiumApi::class)
internal class ReaderActivity : FragmentActivity() {
    private val app get() = application as EBookReaderApplication
    private var appearance = ReaderAppearance()
    private var presentation = ReaderLayout()
    private var palette by mutableStateOf(ReaderTheme.ClassicLight.resolve(false))
    private val highlightPalette = MutableStateFlow(palette)
    private var opened: OpenBook? = null
    private var navigator: EpubNavigatorFragment? = null
    private var bookTitle by mutableStateOf("")
    private var chapters by mutableStateOf(emptyList<Link>())
    private var controlsVisible by mutableStateOf(false)
    private var topControlsHidden by mutableStateOf(true)
    private var bottomControlsHidden by mutableStateOf(true)
    private var dictionarySelection by mutableStateOf<String?>(null)
    private var selectionCaptureJob: Job? = null
    private var selectionMenu by mutableStateOf<SelectionMenu?>(null)
    private var highlightMenu by mutableStateOf<HighlightMenu?>(null)
    private var savedHighlights by mutableStateOf<List<SavedHighlight>?>(null)
    private var noteDraft by mutableStateOf<NoteDraft?>(null)
    private var highlightRemoval by mutableStateOf<SavedHighlight?>(null)
    private val passageBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionMenu?.session?.finish()
            selectionMenu = null
            closeHighlightMenu()
        }
    }
    private val pendingReaderSheetBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            preferenceTab = null
            dictionarySelection = null
            isEnabled = false
        }
    }
    private var displayedLocation by mutableStateOf<Locator?>(null)
    private var chapterTitle by mutableStateOf<String?>(null)
    private var activeChapter by mutableStateOf<Link?>(null)
    private var chapterTitleJob: Job? = null
    private var chaptersByResource = emptyMap<Url, List<Link>>()
    private var showChapters by mutableStateOf(false)
    private var showBookmarks by mutableStateOf(false)
    private var bookmarks by mutableStateOf<List<ResolvedBookmark>?>(null)
    private var bookmarkAtLocation by mutableStateOf<ResolvedBookmark?>(null)
    private var bookmarkMatchReady by mutableStateOf(false)
    private var bookmarkMatchJob: Job? = null
    private var bookmarkWritePending by mutableStateOf(false)
    private var bookmarkIconOverride by mutableStateOf<Boolean?>(null)
    private val bookmarkFeedback = SnackbarHostState()
    private var preferenceTab by mutableStateOf<ReadingPreferenceTab?>(null)
    private var customizationCheckpoint: SavedLocation? = null
    private var repositioning = false
    private val pageEvents = MutableStateFlow(0)
    private var reflowable by mutableStateOf(true)
    private var verticalText by mutableStateOf(false)
    private var didDisplay = false
    private var restoringReader = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var holdingPageDrag = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledPagingTouchSlop }
    private var expectedHref: Url? = null
    private var lastObserved: Locator? = null
    private var newest: SavedLocation? = null
    private var newestTime = 0L
    private var queued: SavedLocation? = null
    private var saveJob: Job? = null
    private var anchorJob: Job? = null
    private var lastSaveTime = 0L
    private var footerMarginResource: Url? = null
    private var footerMarginJob: Job? = null

    private val pageFooter get() = findViewById<TextView>(R.id.reader_page_number)
    private val footerHeight get() = resources.getDimensionPixelSize(R.dimen.reader_footer_height)

    private val layoutKey: String
        get() = resources.configuration.run {
            val mode = if (appearance.fullscreen) ":immersive" else ""
            "${screenWidthDp}x${screenHeightDp}:$densityDpi:$fontScale$mode:overlay:padded-footer:$presentation"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        restoringReader = savedInstanceState != null
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.reader_fade_in, R.anim.reader_hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.reader_hold, R.anim.reader_fade_out)
        }
        // The real factory needs an asynchronously opened publication. Remove the restored dummy
        // before it reaches onResume, then build the real navigator from our durable checkpoint.
        supportFragmentManager.findFragmentByTag(NavigatorTag)?.let { restored ->
            supportFragmentManager.commitNow { remove(restored) }
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        appearance = app.readerAppearance.appearance.value ?: ReaderAppearance()
        setContentView(R.layout.activity_reader)
        val root = findViewById<ReaderSelectionLayout>(R.id.reader_root)
        root.canShowSelection = {
            didDisplay && reflowable && navigator != null && dictionarySelection == null && noteDraft == null &&
                !showBookmarks && !showChapters && preferenceTab == null && highlightRemoval == null
        }
        root.onSelectionAnchor = { session, anchor ->
            if (selectionMenu?.session != session) {
                closeHighlightMenu()
                hideControlsForPassage()
            }
            selectionMenu = SelectionMenu(session, anchor)
            passageBack.isEnabled = true
        }
        root.onSelectionEnd = { session ->
            if (selectionMenu?.session == session) {
                selectionMenu = null
                passageBack.isEnabled = highlightMenu != null
            }
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    keepPublicationOffline(v)
                }
            },
            true,
        )
        val topControls = findViewById<ComposeView>(R.id.reader_top_controls)
        val bottomControls = findViewById<ComposeView>(R.id.reader_bottom_controls)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val physical = Insets.max(
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()),
                if (appearance.fullscreen) roundedCornerInsets(insets) else Insets.NONE,
            )
            val bars = if (appearance.fullscreen) {
                insets.getInsets(WindowInsetsCompat.Type.captionBar())
            } else {
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            }
            val padding = Insets.max(physical, bars)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            val controlInsets = Insets.max(
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()),
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()),
            )
            val controlLeft = (controlInsets.left - padding.left).coerceAtLeast(0)
            val controlRight = (controlInsets.right - padding.right).coerceAtLeast(0)
            topControls.setPadding(controlLeft, (controlInsets.top - padding.top).coerceAtLeast(0), controlRight, 0)
            bottomControls.setPadding(controlLeft, 0, controlRight, maxOf(footerHeight, controlInsets.bottom - padding.bottom))
            WindowInsetsCompat.CONSUMED
        }
        applyPalette()
        applyBarVisibility()

        topControls.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReaderColors(palette) {
                    ReaderTopControls(
                        visible = controlsVisible,
                        title = bookTitle,
                        chapterTitle = chapterTitle,
                        bookmarked = bookmarkIconOverride ?: (bookmarkAtLocation != null),
                        canToggleBookmark = bookmarkMatchReady && !bookmarkWritePending,
                        onToggleBookmark = ::toggleBookmark,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onHidden = { readerControlHidden(top = true) },
                    )
                }
            }
        }
        bottomControls.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ReaderColors(palette) {
                    Column {
                        if (!showBookmarks && dictionarySelection == null) {
                            ReaderBookmarkFeedback(bookmarkFeedback, Modifier.padding(horizontal = 16.dp))
                        }
                        ReaderBottomControls(
                            visible = controlsVisible,
                            progression = displayedLocation?.locations?.totalProgression,
                            canShowChapters = chapters.isNotEmpty(),
                            canShowBookmarks = bookmarks != null,
                            onShowChapters = { showChapters = true },
                            onShowBookmarks = { showBookmarks = true },
                            onShowAppearance = { openPreferences(ReadingPreferenceTab.Appearance) },
                            onShowSettings = { openPreferences(ReadingPreferenceTab.Text) },
                            onHidden = { readerControlHidden(top = false) },
                        )
                    }
                    if (showBookmarks) {
                        ReaderSavedPassages(
                            bookmarks = bookmarks.orEmpty(),
                            highlights = savedHighlights,
                            feedback = bookmarkFeedback,
                            deleting = bookmarkWritePending,
                            onClose = { showBookmarks = false },
                            onOpen = ::openBookmark,
                            onDelete = ::removeBookmark,
                            onOpenHighlight = ::openHighlight,
                            onEditNote = ::editHighlightNote,
                            onRemoveNote = ::removeHighlightNote,
                            onRemoveHighlight = ::requestHighlightRemoval,
                            onColor = ::changeHighlightColor,
                        )
                    }
                    if (showChapters) {
                        ReaderChapters(chapters, activeChapter, onClose = { showChapters = false }) { chapter ->
                            readerNavigated()
                            navigator?.go(chapter, animated = false)
                            showChapters = false
                        }
                    }
                    preferenceTab?.takeIf { topControlsHidden && bottomControlsHidden }?.let { tab ->
                        ReadingPreferencesSheet(
                            app.readerAppearance, app.scope, tab, appearance.fullscreen, palette.dark, reflowable,
                            verticalText = verticalText,
                        ) {
                            preferenceTab = null
                        }
                    }
                    dictionarySelection?.takeIf { topControlsHidden && bottomControlsHidden }?.let { selection ->
                        ReaderDictionarySheet(selection, app.offlineDictionary, appearance.fullscreen, palette.dark) {
                            dictionarySelection = null
                        }
                    }
                    selectionMenu?.let { menu ->
                        ReaderHighlightMenu(
                            menu.anchor,
                            selected = null,
                            actions = listOf(
                                ReaderMenuAction(R.string.highlight_copy, R.drawable.ic_passage_copy) {
                                    useSelection(menu.session) { locator -> copyPassage(locator.text.highlight.orEmpty()) }
                                },
                                ReaderMenuAction(R.string.reader_define, R.drawable.ic_search) {
                                    useSelection(menu.session, allowEmpty = true) { locator ->
                                        pendingReaderSheetBack.isEnabled = !(topControlsHidden && bottomControlsHidden)
                                        dictionarySelection = locator.text.highlight.orEmpty().take(257)
                                    }
                                },
                                ReaderMenuAction(R.string.highlight_note, R.drawable.ic_passage_note) {
                                    useSelection(menu.session) { noteDraft = passageDraft(it) }
                                },
                            ),
                            onColor = { color -> useSelection(menu.session) { locator ->
                                passageDraft(locator)?.let { savePassage(it, color) }
                            } },
                        )
                    }
                    highlightMenu?.let { menu ->
                        savedHighlights?.firstOrNull { it.record.highlightId == menu.id }?.let { entry ->
                            ReaderHighlightMenu(
                                menu.anchor,
                                selected = entry.color,
                                actions = listOf(
                                    ReaderMenuAction(R.string.highlight_copy, R.drawable.ic_passage_copy) {
                                        closeHighlightMenu()
                                        copyPassage(entry.record.selectedText)
                                    },
                                    ReaderMenuAction(
                                        if (entry.record.note == null) R.string.highlight_add_note else R.string.highlight_edit_note,
                                        R.drawable.ic_passage_note,
                                    ) { editHighlightNote(entry) },
                                    ReaderMenuAction(R.string.highlight_remove, R.drawable.ic_remove_history) { requestHighlightRemoval(entry) },
                                ),
                                onColor = { changeHighlightColor(entry, it) },
                                onDismiss = ::closeHighlightMenu,
                            )
                        }
                    }
                    noteDraft?.let { draft ->
                        ReaderNoteEditor(
                            passage = draft.locator.text.highlight.orEmpty(),
                            chapter = draft.chapter,
                            note = draft.existing?.record?.note,
                            onDismiss = { noteDraft = null },
                            onSave = { note ->
                                noteDraft = null
                                savePassage(draft, HighlightColor.Yellow, note)
                            },
                            onRemoveNote = draft.existing?.takeIf { it.record.note != null }?.let { entry ->
                                { noteDraft = null; removeHighlightNote(entry) }
                            },
                        )
                    }
                    highlightRemoval?.let { entry ->
                        AlertDialog(
                            onDismissRequest = { highlightRemoval = null },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            title = { ReaderPopupTitle(R.string.highlight_remove_full) },
                            text = { Text(stringResource(R.string.highlight_remove_confirm)) },
                            dismissButton = { TextButton({ highlightRemoval = null }) { Text(stringResource(R.string.library_cancel)) } },
                            confirmButton = { TextButton({ highlightRemoval = null; deleteHighlight(entry) }) { Text(stringResource(R.string.highlight_remove)) } },
                        )
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, pendingReaderSheetBack)
        onBackPressedDispatcher.addCallback(this, passageBack)
        showMessage(R.string.loading_book)

        val bookId = intent.getStringExtra(BookId)
        if (bookId == null) {
            showMessage(R.string.book_missing)
            return
        }
        lifecycleScope.launch {
            try {
                appearance = app.readerAppearance.appearance.filterNotNull().first()
                applyPalette()
                applyBarVisibility()
                ViewCompat.requestApplyInsets(root)
                launch {
                    app.readerAppearance.appearance.filterNotNull().map { it.theme }.distinctUntilChanged().collect { theme ->
                        appearance = appearance.copy(theme = theme)
                        applyPalette()
                    }
                }
                launch {
                    app.readerAppearance.appearance.filterNotNull().map { it.showPageNumbers }.distinctUntilChanged().collect { enabled ->
                        appearance = appearance.copy(showPageNumbers = enabled)
                        updatePageFooterVisibility()
                    }
                }
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        app.readerAppearance.appearance.filterNotNull()
                            .map { it.keepScreenAwake }
                            .distinctUntilChanged()
                            .collect { enabled ->
                                if (enabled) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            }
                    }
                }
                val book = app.library.openBook(bookId)
                opened = book
                launch {
                    try {
                        app.library.bookmarks(book.book).collect {
                            bookmarks = it
                            refreshBookmarkMatch()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e("ReaderActivity", "Could not load bookmarks", error)
                        showBookmarkFeedback(R.string.reader_bookmark_error)
                    }
                }
                launch {
                    combine(
                        app.library.observeBook(bookId).filterNotNull(),
                        app.libraryLayout.organization.filterNotNull(),
                    ) { current, organization -> current.displayName(organization.displayName) }
                        .distinctUntilChanged().collect { name -> bookTitle = name ?: getString(R.string.untitled_book) }
                }
                reflowable = book.publication.metadata.layout != Layout.FIXED
                val latestLayout = app.readerAppearance.appearance.value?.layout ?: appearance.layout
                presentation = if (reflowable) latestLayout.effectivePresentation() else ReaderLayout(verticalMargins = latestLayout.verticalMargins)
                applyViewportSpacing()
                val stored = book.readingState
                val savedGeneration = savedInstanceState?.takeIf { it.containsKey(ReadingGeneration) }?.getLong(ReadingGeneration)
                val inMemory = savedInstanceState?.getString(Location)
                    ?.takeIf { savedGeneration == book.readingGeneration }
                    ?.takeIf { savedInstanceState.getLong(LocationTime) >= (stored?.positionUpdatedAt ?: 0) }
                    ?.let { SavedLocation.decode(it, book.book.assetSha256) }
                val checkpoint = inMemory ?: SavedLocation.decode(stored?.locator, book.book.assetSha256)
                val initial = book.publication.resumeLocator(
                    checkpoint,
                    layoutKey,
                    stored?.progression,
                )
                customizationCheckpoint = checkpoint?.takeIf {
                    reflowable && it.reflowAnchor != null && initial?.href?.removeFragment()?.isEquivalent(it.locator.href.removeFragment()) == true
                }
                lifecycle.withResumed { attachNavigator(book, initial, inMemory) }
                if (reflowable) {
                    // StateFlow coalesces new values while one native reflow is being restored.
                    app.readerAppearance.appearance.filterNotNull().map { it.layout.effectivePresentation() }
                        .distinctUntilChanged().collect { updated ->
                            try {
                                applyPresentation(updated)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: IllegalStateException) {
                                Log.w("ReaderActivity", "Could not restore the passage after a preference change", error)
                            }
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ReaderActivity", "Could not open book", error)
                showMessage(
                    if (error is BookFailure) bookProblemMessage(error.problem) else R.string.book_unreadable,
                )
            }
        }
    }

    private fun roundedCornerInsets(insets: WindowInsetsCompat): Insets {
        if (Build.VERSION.SDK_INT < 31) return Insets.NONE
        val platformInsets = insets.toWindowInsets() ?: return Insets.NONE
        // The diagonal of the rounded corner gives a small safe margin without reserving
        // the full height of a system bar.
        fun margin(position: Int) = ((platformInsets.getRoundedCorner(position)?.radius ?: 0) * 0.3f).toInt()
        val topLeft = margin(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = margin(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeft = margin(RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRight = margin(RoundedCorner.POSITION_BOTTOM_RIGHT)
        return Insets.of(
            maxOf(topLeft, bottomLeft), maxOf(topLeft, topRight),
            maxOf(topRight, bottomRight), maxOf(bottomLeft, bottomRight),
        )
    }

    private fun applyPalette() {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val resolved = appearance.theme.resolve(systemDark)
        val changed = palette != resolved
        palette = resolved
        highlightPalette.value = resolved
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        findViewById<View>(R.id.reader_root).setBackgroundColor(palette.background)
        pageFooter.setTextColor(ColorUtils.setAlphaComponent(palette.text, 184))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
        if (changed) navigator?.submitPreferences(navigatorPreferences())
    }

    private fun applyBarVisibility() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (appearance.fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBarVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPalette()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Observe only for checkpointing. Readium remains the sole gesture handler.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                holdingPageDrag = false
            }
            MotionEvent.ACTION_MOVE -> if (!holdingPageDrag) {
                val x = abs(event.x - touchStartX)
                holdingPageDrag = x > touchSlop && x > abs(event.y - touchStartY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> holdingPageDrag = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isPageBeingDragged() = didDisplay && holdingPageDrag && navigator?.settings?.value?.scroll == false

    private fun navigatorPreferences() = presentation.toNavigatorPreferences(palette, resources.configuration.fontScale.toDouble())

    private fun openPreferences(tab: ReadingPreferenceTab) {
        controlsVisible = false
        refreshBookmarkMatch()
        pendingReaderSheetBack.isEnabled = !(topControlsHidden && bottomControlsHidden)
        chapterTitleJob?.cancel()
        verticalText = navigator?.settings?.value?.verticalText == true
        preferenceTab = tab
    }

    private fun hideControlsForPassage() {
        controlsVisible = false
        refreshBookmarkMatch()
        chapterTitleJob?.cancel()
    }

    private fun useSelection(session: ReaderSelectionSession, allowEmpty: Boolean = false, action: (Locator) -> Unit) {
        val reader = navigator ?: return
        selectionCaptureJob?.cancel()
        selectionCaptureJob = lifecycleScope.launch {
            val selection = withTimeoutOrNull(1000) {
                reader.currentSelection()?.locator
            }
            if (!session.finish()) return@launch
            reader.clearSelection()
            hideControlsForPassage()
            when {
                allowEmpty -> action(selection ?: reader.currentLocator.value.copy(text = Locator.Text()))
                selection == null || selection.text.highlight.isNullOrBlank() ->
                    showHighlightFeedback(R.string.highlight_selection_missing)
                else -> action(selection)
            }
        }
    }

    private fun copyPassage(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.highlight_copy), text),
        )
    }

    private fun passageDraft(locator: Locator): NoteDraft? {
        if (locator.text.highlight.orEmpty().length > MaxHighlightLength) {
            showHighlightFeedback(R.string.highlight_selection_too_long)
            return null
        }
        val resource = locator.href.removeFragment().normalize()
        val entries = chaptersByResource[resource].orEmpty()
        val fragments = listOfNotNull(locator.href.fragment) + locator.locations.fragments
        val chapter = entries.firstOrNull { it.url().fragment?.let(fragments::contains) == true }?.title
            ?: locator.title
            ?: entries.firstOrNull { it.url().fragment == null }?.title
        val spineIndex = opened?.publication?.readingOrder?.indexOfFirst { it.url().removeFragment().isEquivalent(resource) }
        return NoteDraft(locator, chapter, spineIndex?.takeIf { it >= 0 } ?: Int.MAX_VALUE)
    }

    private fun savePassage(draft: NoteDraft, color: HighlightColor, note: String? = null) {
        val bookId = opened?.book?.bookId ?: return
        writeHighlight {
            val existing = draft.existing
            val changed = if (existing != null) {
                app.library.setHighlightNote(bookId, existing.record.highlightId, note)
            } else {
                app.library.saveHighlight(bookId, draft.locator, draft.chapter, draft.spineIndex, color, note)
                confirmReaderAction()
                true
            }
            if (changed) showHighlightFeedback(if (note == null) R.string.highlight_saved else R.string.highlight_note_saved)
        }
    }

    private fun closeHighlightMenu() {
        highlightMenu = null
        passageBack.isEnabled = selectionMenu != null
    }

    private fun editHighlightNote(entry: SavedHighlight) {
        closeHighlightMenu()
        hideControlsForPassage()
        noteDraft = NoteDraft(entry.locator, entry.record.chapter, entry.record.spineIndex, entry)
    }

    private fun removeHighlightNote(entry: SavedHighlight) {
        writeHighlight {
            if (app.library.setHighlightNote(entry.record.bookId, entry.record.highlightId, null)) {
                showHighlightFeedback(R.string.highlight_note_removed)
            }
        }
    }

    private fun changeHighlightColor(entry: SavedHighlight, color: HighlightColor) {
        closeHighlightMenu()
        writeHighlight {
            if (app.library.setHighlightColor(entry.record.bookId, entry.record.highlightId, color)) {
                confirmReaderAction()
                showHighlightFeedback(R.string.highlight_saved)
            }
        }
    }

    private fun confirmReaderAction() {
        if (isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        window.decorView.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS
        )
    }

    private fun requestHighlightRemoval(entry: SavedHighlight) {
        closeHighlightMenu()
        if (entry.record.note != null) highlightRemoval = entry else deleteHighlight(entry)
    }

    private fun deleteHighlight(entry: SavedHighlight) {
        writeHighlight {
            val removed = app.library.removeHighlight(entry.record.bookId, entry.record.highlightId) ?: return@writeHighlight
            showHighlightFeedback(
                if (removed.note != null) R.string.highlight_removed_with_note else R.string.highlight_removed,
                undo = removed,
            )
        }
    }

    private fun writeHighlight(change: suspend () -> Unit) {
        app.scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ReaderActivity", "Could not save passage", error)
                showHighlightFeedback(R.string.highlight_error)
            }
        }
    }

    private fun showHighlightFeedback(@StringRes message: Int, undo: Highlight? = null) {
        if (isDestroyed) return
        lifecycleScope.launch {
            bookmarkFeedback.currentSnackbarData?.dismiss()
            val result = bookmarkFeedback.showSnackbar(
                getString(message),
                actionLabel = undo?.let { getString(R.string.undo) },
                withDismissAction = undo != null,
                duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (undo != null && result == SnackbarResult.ActionPerformed) {
                writeHighlight {
                    if (app.library.restoreHighlight(undo)) showHighlightFeedback(R.string.highlight_restore)
                }
            }
        }
    }

    private fun openHighlight(entry: SavedHighlight) {
        val reader = navigator ?: return
        val book = opened ?: return
        if (!book.publication.readingOrder.any { it.url().removeFragment().isEquivalent(entry.locator.href) }) {
            showHighlightFeedback(R.string.highlight_unavailable)
            return
        }
        readerNavigated()
        if (reader.go(entry.locator, animated = false)) showBookmarks = false
        else showHighlightFeedback(R.string.highlight_unavailable)
    }

    private fun bindHighlights(reader: EpubNavigatorFragment, book: OpenBook) {
        if (!reflowable || !reader.supportsDecorationStyle(Decoration.Style.Highlight::class)) {
            savedHighlights = emptyList()
            return
        }
        val root = findViewById<View>(R.id.reader_root)
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id
                val local = Rect()
                val bounds = event.rect?.takeUnless { it.isEmpty }
                if (bounds != null) {
                    bounds.roundOut(local)
                } else {
                    val point = event.point ?: return false
                    local.set(point.x.roundToInt(), point.y.roundToInt(), point.x.roundToInt() + 1, point.y.roundToInt() + 1)
                }
                // Readium dispatches activation through the WebView bridge.
                root.post {
                    if (isDestroyed || selectionMenu != null || dictionarySelection != null || noteDraft != null ||
                        showBookmarks || showChapters || preferenceTab != null || highlightRemoval != null
                    ) return@post
                    val navigatorView = reader.view ?: return@post
                    if (!reader.currentLocator.value.href.removeFragment().isEquivalent(event.decoration.locator.href.removeFragment())) {
                        return@post
                    }
                    if (savedHighlights?.none { it.record.highlightId == id } != false) return@post
                    hideControlsForPassage()
                    highlightMenu = HighlightMenu(id, root.readerPopupAnchor(navigatorView, local))
                    passageBack.isEnabled = true
                }
                return true
            }
        }
        reader.addDecorationListener(HighlightGroup, listener)
        lifecycleScope.launch {
            try {
                combine(app.library.highlights(book.book.bookId), highlightPalette) { entries, colors ->
                    savedHighlights = entries
                    val current = highlightMenu
                    if (current != null && entries.none { it.record.highlightId == current.id }) closeHighlightMenu()
                    entries.map { entry ->
                        val tint = ColorUtils.blendARGB(entry.color.argb, colors.background, if (colors.dark) 0.35f else 0.08f)
                        Decoration(entry.record.highlightId, entry.locator, Decoration.Style.Highlight(tint))
                    }
                }.distinctUntilChanged().collect { reader.applyDecorations(it, HighlightGroup) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("ReaderActivity", "Could not load highlights", failure)
                showHighlightFeedback(R.string.highlight_error)
            } finally {
                reader.removeDecorationListener(listener)
            }
        }
    }

    // The dictionary grants the app Internet access. Keep Readium WebViews on their intercepted
    // publication resources and local assets, as when the app had no Internet permission.
    private fun keepPublicationOffline(view: View) {
        when (view) {
            is WebView -> view.settings.blockNetworkLoads = true
            is ViewGroup -> view.children.forEach(::keepPublicationOffline)
        }
    }

    private fun refreshBookmarkMatch() {
        bookmarkMatchJob?.cancel()
        bookmarkMatchReady = false
        if (!controlsVisible || repositioning) return
        val reader = navigator ?: return
        val location = displayedLocation ?: return
        val saved = bookmarks ?: return
        // Use a fast native match while checking any more precise HTML anchors.
        bookmarkAtLocation = saved.firstOrNull { location.matchesBookmarkFallback(it.location.locator) }
        if (saved.isEmpty()) {
            bookmarkMatchReady = true
            return
        }
        bookmarkMatchJob = lifecycleScope.launch {
            try {
                bookmarkAtLocation = reader.visibleBookmark(location, saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ReaderActivity", "Could not check visible bookmark anchors", error)
            }
            bookmarkMatchReady = true
        }
    }

    private fun toggleBookmark() {
        if (!bookmarkMatchReady || bookmarkWritePending || repositioning || isPageBeingDragged()) return
        bookmarkAtLocation?.let { existing ->
            removeBookmark(existing)
            return
        }
        val book = opened ?: return
        val reader = navigator ?: return
        val location = displayedLocation ?: return
        val chapter = chapterTitle?.takeIf(String::isNotBlank) ?: location.title
        val layout = layoutKey
        val now = System.currentTimeMillis()
        bookmarkFeedback.currentSnackbarData?.dismiss()
        bookmarkWritePending = true
        bookmarkIconOverride = true
        lifecycleScope.launch {
            val anchor = try {
                if (reflowable) reader.visiblePassage(location, includeBookmarkPoint = true) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ReaderActivity", "Could not capture bookmark text", error)
                null
            }
            if (isPageBeingDragged() || repositioning ||
                displayedLocation?.matchesBookmarkFallback(location) != true ||
                !reader.currentLocator.value.matchesBookmarkFallback(location) || layoutKey != layout
            ) {
                finishBookmarkChange()
                showBookmarkFeedback(R.string.reader_bookmark_error)
                return@launch
            }
            val saved = SavedLocation(location, anchor, layout)
            val spineIndex = book.publication.readingOrder.indexOfFirst {
                it.url().removeFragment().isEquivalent(location.href.removeFragment())
            }.takeIf { it >= 0 } ?: Int.MAX_VALUE
            writeBookmark(R.string.reader_bookmark_saved) {
                val record = app.library.saveBookmark(book.book, saved, chapter, spineIndex, now)
                if (displayedLocation?.matchesBookmarkFallback(location) == true) {
                    bookmarkAtLocation = ResolvedBookmark(record, SavedLocation.decode(record.locator, book.book.assetSha256) ?: saved)
                }
                true
            }
        }
    }

    private fun removeBookmark(bookmark: ResolvedBookmark) {
        if (bookmarkWritePending) return
        if (bookmarkAtLocation == bookmark) bookmarkIconOverride = false
        writeBookmark(R.string.reader_bookmark_removed, undo = bookmark.record) {
            val removed = app.library.removeBookmark(bookmark.record)
            if (bookmarkAtLocation == bookmark) bookmarkAtLocation = null
            removed
        }
    }

    private fun writeBookmark(
        @StringRes message: Int,
        undo: Bookmark? = null,
        change: suspend () -> Boolean,
    ) {
        bookmarkFeedback.currentSnackbarData?.dismiss()
        bookmarkWritePending = true
        // Allow an in-flight Room write to finish if the reader Activity is closed.
        app.scope.launch {
            try {
                if (change() && !isDestroyed) {
                    confirmReaderAction()
                    showBookmarkFeedback(message, undo)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ReaderActivity", "Could not update bookmark", error)
                if (!isDestroyed) showBookmarkFeedback(R.string.reader_bookmark_error)
            } finally {
                finishBookmarkChange()
            }
        }
    }

    private fun finishBookmarkChange() {
        bookmarkWritePending = false
        bookmarkIconOverride = null
    }

    private fun showBookmarkFeedback(@StringRes message: Int, undo: Bookmark? = null) {
        lifecycleScope.launch {
            bookmarkFeedback.currentSnackbarData?.dismiss()
            val result = bookmarkFeedback.showSnackbar(
                message = getString(message),
                actionLabel = undo?.let { getString(R.string.undo) },
                withDismissAction = undo != null,
                duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (undo != null && result == SnackbarResult.ActionPerformed) {
                writeBookmark(R.string.reader_bookmark_restored) {
                    app.library.restoreBookmark(undo)
                    true
                }
            }
        }
    }

    private fun openBookmark(bookmark: ResolvedBookmark) {
        val reader = navigator ?: return
        val book = opened ?: return
        lifecycleScope.launch {
            val target = book.publication.resumeLocator(bookmark.location, layoutKey, fallbackProgression = null)
            if (target != null && showBookmarks) {
                readerNavigated()
                if (reader.go(target, animated = false)) {
                    showBookmarks = false
                    return@launch
                }
            }
            if (showBookmarks) showBookmarkFeedback(R.string.reader_bookmark_open_error)
        }
    }

    private fun readerControlHidden(top: Boolean) {
        if (controlsVisible) return
        if (top) topControlsHidden = true else bottomControlsHidden = true
        if (topControlsHidden && bottomControlsHidden) pendingReaderSheetBack.isEnabled = false
    }

    private fun readerNavigated() {
        if (highlightMenu != null) closeHighlightMenu()
        if (didDisplay && !repositioning && preferenceTab == null) customizationCheckpoint = null
    }

    private fun applyViewportSpacing() {
        val margin = (presentation.verticalMargins * resources.displayMetrics.density).roundToInt()
        val top = resources.getDimensionPixelSize(R.dimen.reader_viewport_top_padding)
        findViewById<View>(R.id.publication_container).setPadding(0, top + margin, 0, footerHeight + margin)
        footerMarginJob?.cancel()
        footerMarginResource = null
        if (presentation.scroll) showPageNumber(0, 0, section = true)
        val fallback = (20 * resources.displayMetrics.density * presentation.horizontalMargins / 100).roundToInt()
        pageFooter.setPadding(0, pageFooter.paddingTop, fallback, 0)
    }

    private fun updatePageFooterVisibility() {
        pageFooter.visibility = if (appearance.showPageNumbers && pageFooter.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPageNumber(page: Int, count: Int, section: Boolean) {
        val valid = page in 1..count
        pageFooter.text = if (valid) getString(R.string.reader_page_number, page, count) else ""
        pageFooter.contentDescription = if (valid) {
            val label = if (section) R.string.reader_page_number_section_description else R.string.reader_page_number_book_description
            getString(label, page, count)
        } else null
        updatePageFooterVisibility()
    }

    private fun alignPageFooter(locator: Locator) {
        val resource = locator.href.removeFragment()
        if (footerMarginResource == resource) return
        footerMarginJob?.cancel()
        footerMarginResource = resource
        footerMarginJob = lifecycleScope.launch {
            // Body fragments account for the reading column and Readium's computed page gutter.
            // Use a fraction of innerWidth so CSS pixels and Android view pixels stay aligned.
            val inset = withTimeoutOrNull(600) {
                navigator?.evaluateJavascript(
                    """
                    (() => {
                        if (!document.body || innerWidth <= 0) return null;
                        const style = getComputedStyle(document.body);
                        const right = Array.from(document.body.getClientRects())
                            .filter(rect => rect.left < innerWidth - 1 && rect.right > 1)
                            .reduce((edge, rect) => Math.max(edge, Math.min(innerWidth, rect.right)), 0);
                        const margin = right > 0 ? innerWidth - right : (parseFloat(style.marginRight) || 0);
                        return (margin + (parseFloat(style.paddingRight) || 0) +
                            (parseFloat(style.borderRightWidth) || 0)) / innerWidth;
                    })()
                    """.trimIndent(),
                )?.toDoubleOrNull()?.takeIf { it in 0.0..0.5 }
            } ?: return@launch
            val width = findViewById<View>(R.id.publication_container).width
            if (footerMarginResource == resource && width > 0) {
                pageFooter.setPadding(0, pageFooter.paddingTop, (inset * width).roundToInt(), 0)
            }
        }
    }

    private suspend fun applyPresentation(updated: ReaderLayout) {
        val reader = navigator ?: return
        if (presentation == updated) return
        if (!didDisplay) {
            presentation = updated
            applyViewportSpacing()
            reader.submitPreferences(navigatorPreferences())
            return
        }
        repositioning = true
        try {
            if (customizationCheckpoint == null) {
                val current = reader.currentLocator.value
                val anchor = reader.visiblePassage(current) ?: newest?.takeIf { it.locator.href == current.href }?.reflowAnchor
                val checkpoint = SavedLocation(current, anchor, layoutKey)
                customizationCheckpoint = checkpoint
                anchorJob?.cancel()
                newest = checkpoint
                newestTime = System.currentTimeMillis()
                flush()
            }

            val shouldScroll = updated.scroll || reader.settings.value.verticalText
            val switchedMode = reader.settings.value.scroll != shouldScroll
            val changedFont = updated.font.bundled.takeIf { presentation.font != updated.font }
            presentation = updated
            applyViewportSpacing()
            reader.submitPreferences(navigatorPreferences())
            val checkpoint = customizationCheckpoint ?: return

            // A mode switch rebuilds Readium's pager. Wait for the CSS of the new WebView before
            // returning to the quote, so Readium's own restoration cannot undo our jump.
            delay(if (switchedMode) 160 else 70)
            val layoutReady = "document.documentElement.style.cssText.includes('readium-scroll-on') === $shouldScroll"
            val ready = if (changedFont == null) layoutReady else """
                (() => {
                    if (!($layoutReady) || !document.body) return false;
                    const family = document.documentElement.style.getPropertyValue('--USER__fontFamily');
                    if (!family.includes('${changedFont.family}')) return false;
                    document.body.getBoundingClientRect();
                    return document.fonts.status === 'loaded';
                })()
            """.trimIndent()
            withTimeoutOrNull(1_700) {
                while (reader.evaluateJavascript(ready) != "true") delay(55)
            }
            val generation = pageEvents.value
            reader.go(checkpoint.reflowAnchor ?: checkpoint.locator, animated = false)
            withTimeoutOrNull(550) { pageEvents.first { it > generation } }
            val restored = reader.currentLocator.value
            lastObserved = restored
            displayedLocation = restored
            if (!shouldScroll) {
                footerMarginResource = null
                alignPageFooter(restored)
            }
        } finally {
            repositioning = false
            refreshBookmarkMatch()
        }
    }

    private fun attachNavigator(book: OpenBook, initial: Locator?, inMemory: SavedLocation?) {
        expectedHref = initial?.href?.removeFragment()
        newest = inMemory
        if (inMemory != null) newestTime = System.currentTimeMillis()
        chapters = book.publication.tableOfContents.ifEmpty { book.publication.readingOrder }
        chaptersByResource = chapters.contentsWithDepth().map { it.first }
            .groupBy { it.url().removeFragment().normalize() }
        val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
            initialLocator = initial,
            initialPreferences = navigatorPreferences(),
            configuration = EpubNavigatorFragment.Configuration {
                decorationTemplates[Decoration.Style.Highlight::class] = HtmlDecorationTemplate.highlight(
                    defaultTint = HighlightColor.Yellow.argb, lineWeight = 0, cornerRadius = 2, alpha = 0.30,
                )
                // ReaderActivity already applies the display cutout and system insets.
                shouldApplyInsetsPadding = false
                addFontFamilyDeclaration(FontFamily.ACCESSIBLE_DFA) {
                    addFontFace { addSource("readium/readium-css/fonts/AccessibleDfA.otf") }
                }
                addFontFamilyDeclaration(FontFamily.IA_WRITER_DUOSPACE) {
                    addFontFace { addSource("readium/readium-css/fonts/iAWriterDuospace-Regular.ttf") }
                }
                servedAssets += "reader-fonts/.*"
                for (font in ReaderFont.entries.mapNotNull { it.bundled }) {
                    addFontFamilyDeclaration(FontFamily(font.family)) {
                        for ((path, style) in listOf("regular" to FontStyle.NORMAL, "italic" to FontStyle.ITALIC)) {
                            addFontFace {
                                setFontStyle(style)
                                val range = font.weightRange
                                if (range == null) setFontWeight(FontWeight.NORMAL) else setFontWeight(range)
                                addSource("${font.assetPath}/$path.ttf")
                            }
                        }
                        if (font.weightRange == null) {
                            addFontFace {
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.BOLD)
                                addSource("${font.assetPath}/bold.ttf")
                            }
                            addFontFace {
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.BOLD)
                                addSource("${font.assetPath}/bolditalic.ttf")
                            }
                        }
                    }
                }
            },
            listener = object : EpubNavigatorFragment.Listener {
                override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
                    readerNavigated()
                    return true
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    // Publications remain offline. Internal links are handled by Readium.
                }

                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    val required = expectedHref ?: book.publication.readingOrder.first().url()
                    if (!didDisplay && href.removeFragment().isEquivalent(required)) {
                        showMessage(R.string.book_corrupt)
                    }
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    if (isPageBeingDragged()) return
                    pageEvents.value++
                    val settings = navigator?.settings?.value
                    if (presentation.scroll || settings?.scroll == true) {
                        showPageNumber(0, 0, section = true)
                    } else {
                        val page = if (settings?.readingProgression == ReadingProgression.RTL) totalPages - pageIndex else pageIndex + 1
                        showPageNumber(page, totalPages, section = true)
                        alignPageFooter(locator)
                    }
                    // Readium invokes this after the initial reflowable layout, even when its
                    // StateFlow value equals the supplied initial locator.
                    if (!didDisplay) onLocation(locator)
                }
            },
        )
        supportFragmentManager.fragmentFactory = factory
        val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
            as EpubNavigatorFragment
        navigator = fragment
        val pageTurns = ReaderPageTurns(fragment)
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (!didDisplay || preferenceTab != null || dictionarySelection != null || noteDraft != null ||
                    selectionMenu != null || highlightRemoval != null
                ) return false
                if (highlightMenu != null) closeHighlightMenu()
                if (pageTurns.onTap(event)) {
                    readerNavigated()
                    return true
                }
                val viewport = fragment.publicationView
                val x = event.point.x
                val y = event.point.y
                val inCenter = x > viewport.width * 0.3f && x < viewport.width * 0.7f &&
                    y > viewport.height * 0.15f && y < viewport.height * 0.85f
                if (!inCenter) return false
                controlsVisible = !controlsVisible
                if (controlsVisible) {
                    topControlsHidden = false
                    bottomControlsHidden = false
                    displayedLocation?.let(::updateChapterTitle)
                }
                else chapterTitleJob?.cancel()
                refreshBookmarkMatch()
                return true
            }

            override fun onDrag(event: DragEvent): Boolean {
                // Readium owns the paginated drag and settling. Only a new locator commits it.
                if (event.type == DragEvent.Type.Start && fragment.settings.value.scroll) readerNavigated()
                return false
            }

            override fun onKey(event: KeyEvent): Boolean {
                val handled = pageTurns.onKey(event)
                if (handled) readerNavigated()
                return handled
            }
        })
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fragment.currentLocator.drop(1).collect { onLocation(it) }
        }
        supportFragmentManager.commitNow { add(R.id.publication_container, fragment, NavigatorTag) }
        bindHighlights(fragment, book)
    }

    private fun updateChapterTitle(locator: Locator) {
        chapterTitleJob?.cancel()
        val entries = chaptersByResource[locator.href.removeFragment().normalize()].orEmpty()
        val fragments = listOfNotNull(locator.href.fragment) + locator.locations.fragments
        val fallback = entries.firstOrNull { it.url().fragment?.let(fragments::contains) == true }
            ?: entries.firstOrNull { it.url().fragment == null }
        fun selectChapter(link: Link?) {
            activeChapter = link
            chapterTitle = link?.title?.takeIf(String::isNotBlank) ?: locator.title
        }
        selectChapter(fallback)
        val anchored = entries.mapNotNull { link -> link.url().fragment?.let { it to link } }
        if (anchored.isEmpty() || anchored.size > 1_000) return
        val ids = JSONArray(anchored.map { it.first })
        val scrolling = navigator?.settings?.value?.scroll ?: presentation.scroll
        chapterTitleJob = lifecycleScope.launch {
            // Readium's page locator identifies the spine item. Resolve fragment-level TOC
            // titles from the active document without modifying or reloading its layout.
            val index = navigator?.evaluateJavascript(
                """
                (() => {
                    let current = null, index = -1;
                    const style = getComputedStyle(document.body);
                    if (style.writingMode !== 'horizontal-tb') return -1;
                    const rtl = style.direction === 'rtl';
                    $ids.forEach((id, i) => {
                        const node = document.getElementById(id);
                        const rect = node && node.getClientRects()[0];
                        const beforePageEnd = rect && ($scrolling ? rect.top < innerHeight : (rtl ? rect.right > 0 : rect.left < innerWidth));
                        if (beforePageEnd && (!current || (current.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING))) {
                            current = node;
                            index = i;
                        }
                    });
                    return index;
                })();
                """.trimIndent(),
            )?.toIntOrNull()
            if (displayedLocation == locator && controlsVisible) {
                selectChapter(index?.let { anchored.getOrNull(it)?.second } ?: fallback)
            }
        }
    }

    private fun onLocation(locator: Locator) {
        val book = opened ?: return
        if (repositioning || isPageBeingDragged() || (customizationCheckpoint != null && preferenceTab != null)) return
        if (!didDisplay && expectedHref?.isEquivalent(locator.href.removeFragment()) == false) return
        if (lastObserved == locator) return
        val previous = lastObserved
        if (previous != null && (previous.href != locator.href || previous.locations.progression != locator.locations.progression)) {
            readerNavigated()
            if (!locator.matchesBookmarkFallback(previous)) bookmarkIconOverride = null
        }
        if (!reflowable) {
            val spine = book.publication.readingOrder
            val index = spine.indexOfFirst { it.url().isEquivalent(locator.href.removeFragment()) }
            showPageNumber(index + 1, spine.size, section = false)
        }
        lastObserved = locator
        displayedLocation = locator
        if (controlsVisible) updateChapterTitle(locator)
        refreshBookmarkMatch()
        if (!didDisplay) {
            didDisplay = true
            val loading = findViewById<View>(R.id.reader_message)
            if (restoringReader) {
                loading.visibility = View.GONE
            } else {
                loading.animate().alpha(0f).setDuration(Motion.Panel.toLong())
                    .setInterpolator(AnimationUtils.loadInterpolator(this, android.R.interpolator.fast_out_slow_in))
                    .withEndAction { loading.visibility = View.GONE }
                    .start()
            }
            enqueue(null, System.currentTimeMillis())
            if (book.readingState?.locator != null || newest != null) return
        }
        if (customizationCheckpoint != null) return

        newest = SavedLocation(locator, layout = layoutKey)
        newestTime = System.currentTimeMillis()
        if (book.publication.metadata.layout != Layout.FIXED) {
            anchorJob?.cancel()
            anchorJob = lifecycleScope.launch {
                val anchor = navigator?.visiblePassage(locator)
                // Retain the native page locator for identical layouts. Only use a short textual
                // anchor across reflow so a malformed/huge document cannot balloon the checkpoint.
                if (newest?.locator == locator &&
                    anchor?.href?.isEquivalent(locator.href) == true &&
                    (anchor.text.highlight?.length ?: 0) in 1..4000
                ) {
                    newest = newest?.copy(reflowAnchor = anchor)
                }
            }
        }
        if (saveJob?.isActive != true) {
            saveJob = lifecycleScope.launch {
                delay((CheckpointInterval - (SystemClock.elapsedRealtime() - lastSaveTime)).coerceIn(150, CheckpointInterval))
                flush()
            }
        }
    }

    private fun flush() {
        val checkpoint = newest ?: return
        if (queued == checkpoint) return
        queued = checkpoint
        lastSaveTime = SystemClock.elapsedRealtime()
        enqueue(checkpoint, newestTime)
    }

    private fun enqueue(location: SavedLocation?, time: Long) {
        val session = opened ?: return
        val chapter = location?.locator?.let { locator ->
            val fragments = listOfNotNull(locator.href.fragment) + locator.locations.fragments
            val entries = chaptersByResource[locator.href.removeFragment().normalize()].orEmpty()
            chapterTitle?.takeIf { controlsVisible && displayedLocation == locator }
                ?: entries.firstOrNull { it.url().fragment?.let(fragments::contains) == true }?.title
                ?: locator.title
                ?: entries.firstOrNull { it.url().fragment == null }?.title
        }
        // Room writes outlive this Activity so pressing Back does not cancel the final checkpoint.
        app.scope.launch {
            try {
                app.library.recordReading(session, time, location, chapter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (queued == location) queued = null
                Log.e("ReaderActivity", "Could not save reading position", error)
            }
        }
    }

    private fun showMessage(@StringRes message: Int) {
        findViewById<ComposeView>(R.id.reader_message).apply {
            animate().cancel()
            alpha = 1f
            visibility = View.VISIBLE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReaderColors(palette) { ReaderMessage(message) } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        newest?.let { location ->
            opened?.let { book ->
                outState.putString(Location, location.encode(book.book.assetSha256))
                outState.putLong(LocationTime, newestTime)
                book.readingGeneration?.let { outState.putLong(ReadingGeneration, it) }
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        flush()
        super.onPause()
    }

    override fun onStop() {
        flush()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        opened?.close()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.reader_hold, R.anim.reader_fade_out)
    }

    companion object {
        private const val BookId = "bookId"
        private const val NavigatorTag = "epub"
        private const val Location = "location"
        private const val LocationTime = "locationTime"
        private const val ReadingGeneration = "readingGeneration"
        private const val CheckpointInterval = 750L
        private const val HighlightGroup = "kana-highlights"

        fun intent(context: Context, bookId: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(BookId, bookId)
    }
}
