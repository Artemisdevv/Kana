package com.artemis.ebookreader

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.PointF
import android.system.Os
import android.view.View
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.withResumed
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.artemis.ebookreader.data.library.Book
import com.artemis.ebookreader.data.library.DeleteFailure
import com.artemis.ebookreader.data.library.HighlightColor
import com.artemis.ebookreader.data.library.ImportResult
import com.artemis.ebookreader.data.library.LibraryDatabase
import com.artemis.ebookreader.data.library.LibraryRepository
import com.artemis.ebookreader.data.library.RenameResult
import com.artemis.ebookreader.data.library.StorageCategory
import com.artemis.ebookreader.data.library.StoragePaths
import com.artemis.ebookreader.data.library.IntegrityIssueKind
import com.artemis.ebookreader.data.library.scanLibraryStorage
import com.artemis.ebookreader.feature.history.groupHistory
import com.artemis.ebookreader.feature.reader.ReaderActivity
import com.artemis.ebookreader.feature.reader.ReaderPageTurns
import com.artemis.ebookreader.publication.BookFailure
import com.artemis.ebookreader.publication.BookProblem
import com.artemis.ebookreader.publication.SavedLocation
import com.artemis.ebookreader.publication.bookmarkPoint
import com.artemis.ebookreader.publication.matchesBookmarkFallback
import com.artemis.ebookreader.publication.resumeLocator
import com.artemis.ebookreader.publication.withChapterBreaks
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.use

class ReadingWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "workflow-${UUID.randomUUID()}.db"
    private val files = File(context.cacheDir, name)
    private lateinit var database: LibraryDatabase
    private lateinit var library: LibraryRepository

    @Before
    fun openDatabase() {
        database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        library = LibraryRepository(context, database.libraryDao(), files)
    }

    @After
    fun removeDatabase() {
        database.close()
        context.deleteDatabase(name)
        files.deleteRecursively()
    }

    @Test
    fun concurrentDuplicatesKeepOnePersistentBookAndCover() = runBlocking {
        val content = epub("Harbor Test")
        val first = async { library.importBook("first.epub") { content.inputStream() } }
        val second = async { library.importBook("renamed.epub") { content.inputStream() } }
        assertEquals(setOf(ImportResult.Added, ImportResult.Duplicate), setOf(first.await(), second.await()))
        val before = library.library.first().single()
        assertEquals("Harbor Test", before.book.title)
        assertEquals("Test Author", before.book.authorLine)
        assertTrue(library.cover(before.book.assetSha256).isFile)
        database.close()
        openDatabase()
        assertEquals(before, library.library.first().single())
        library.openBook(before.book.bookId).use {
            assertEquals(2, it.publication.readingOrder.size)
            assertNull(it.readingState)
        }
    }

    @Test
    fun renamePreservesIdentityReadingDataAndByteForByteExport() = runBlocking {
        val book = add("Rename fixture")
        val other = add("Existing filename")
        val checkpoint = secondChapter(book)
        library.recordReading(book, 100, checkpoint)
        val bookmark = library.saveBookmark(book, checkpoint, "Second chapter", 1, 200)
        val before = database.libraryDao().readingState(book.bookId)
        val source = File(files, "books/${book.assetSha256}.epub")
        val bytes = source.readBytes()

        assertEquals(RenameResult.Conflict, library.renameBook(book.bookId, checkNotNull(other.sourceName).uppercase()))
        assertEquals(RenameResult.InvalidName, library.renameBook(book.bookId, "../bad"))
        assertEquals(RenameResult.Renamed, library.renameBook(book.bookId, "Renamed.EPUB"))
        val renamed = checkNotNull(library.details(book.bookId).first()).shelf.book
        assertEquals(book.copy(sourceName = "Renamed.epub"), renamed)
        assertEquals(before, database.libraryDao().readingState(book.bookId))
        assertEquals(bookmark, library.bookmarks(renamed).first().single().record)
        assertEquals(ImportResult.Duplicate, library.importBook("different.epub") { bytes.inputStream() })

        val exported = ByteArrayOutputStream()
        library.writeBookCopy(book.bookId) { exported }
        assertArrayEquals(bytes, exported.toByteArray())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun progressResetRejectsWritesFromAnEarlierReaderEvenAfterReopening() = runBlocking {
        val book = add("Stale locator")
        val checkpoint = secondChapter(book)
        library.openBook(book.bookId).use { old ->
            library.recordReading(old, 100, checkpoint)
            library.saveBookmark(book, checkpoint, "Second chapter", 1, 120)
            assertNotNull(library.resetReading(book.bookId))
            library.recordReading(old, 150, null)
            assertTrue(library.history.first().isEmpty())

            library.openBook(book.bookId).use { fresh ->
                assertNull(fresh.readingState)
                library.recordReading(fresh, 200, null)
                library.recordReading(old, 250, checkpoint)
                val restored = checkNotNull(database.libraryDao().readingState(book.bookId))
                assertNull(restored.locator)
                assertEquals(200L, restored.lastReadAt)
            }
            assertEquals(1, checkNotNull(library.details(book.bookId).first()).bookmarkCount)
        }
    }

    @Test
    fun deletionRollsBackStagedFilesWhenRoomFailsThenCleansOwnedData() = runBlocking {
        val book = add("Safe deletion")
        val original = File(files, "original.epub").apply {
            writeBytes(File(files, "books/${book.assetSha256}.epub").readBytes())
        }
        val chapter = secondChapter(book)
        library.recordReading(book, 100, chapter)
        library.saveBookmark(book, chapter, "Second chapter", 1, 200)

        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER block_book_delete BEFORE DELETE ON imported_files
            BEGIN SELECT RAISE(ABORT, 'forced test failure'); END
        """.trimIndent())
        val failure = runCatching { library.deleteBook(book.bookId) }.exceptionOrNull() as DeleteFailure
        assertFalse(failure.recoveryPending)
        assertArrayEquals(original.readBytes(), File(files, "books/${book.assetSha256}.epub").readBytes())
        assertTrue(library.cover(book.assetSha256).isFile)
        assertEquals(1, library.history.first().size)
        assertEquals(1, library.bookmarks(book).first().size)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_book_delete")
        assertFalse(checkNotNull(library.deleteBook(book.bookId)).cleanupPending)
        assertTrue(original.isFile)
        assertFalse(File(files, "books/${book.assetSha256}.epub").exists())
        assertFalse(library.cover(book.assetSha256).exists())
        assertNull(library.details(book.bookId).first())
        assertNull(database.libraryDao().readingState(book.bookId))
        assertTrue(library.bookmarks(book).first().isEmpty())
        assertTrue(library.history.first().isEmpty())
        assertTrue(library.recent.first().isEmpty())
    }

    @Test
    fun startupRestoresStagedFilesFromAnInterruptedDeletion() = runBlocking {
        val book = add("Deletion recovery")
        val source = File(files, "books/${book.assetSha256}.epub")
        val cover = library.cover(book.assetSha256)
        val trash = File(files, "book-trash").apply { mkdirs() }
        assertTrue(source.renameTo(File(trash, source.name)))
        assertTrue(cover.renameTo(File(trash, cover.name)))
        val reopened = LibraryRepository(context, database.libraryDao(), files)
        reopened.recover()
        assertTrue(source.isFile)
        assertTrue(cover.isFile)
        assertTrue(reopened.library.first().single().available)
    }

    @Test
    fun cancellingOneSelectedCopyLeavesCompletedExportsAndImportedFilesIntact() = runBlocking {
        val first = add("Completed export")
        val second = add("Cancelled export")
        val firstCopy = ByteArrayOutputStream()
        library.writeBookCopy(first.bookId) { firstCopy }
        val firstSource = File(files, "books/${first.assetSha256}.epub")
        val secondSource = File(files, "books/${second.assetSha256}.epub")
        val secondBytes = secondSource.readBytes()
        val failure = runCatching {
            var checks = 0
            library.writeBookCopy(second.bookId, checkCancellation = {
                if (++checks > 1) throw CancellationException("Fixture cancellation")
            }) { ByteArrayOutputStream() }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertArrayEquals(firstSource.readBytes(), firstCopy.toByteArray())
        assertArrayEquals(secondBytes, secondSource.readBytes())
        assertEquals(setOf(first.bookId, second.bookId), library.library.first().map { it.book.bookId }.toSet())
    }

    @Test
    fun aSelectedBookDeletionFailureLeavesThatBookAvailableForRetry() = runBlocking {
        val first = add("First selected deletion")
        val second = add("Second selected deletion")
        val position = secondChapter(second)
        library.recordReading(second, 100, position)
        library.saveBookmark(second, position, "Second chapter", 1, 120)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_second_delete BEFORE DELETE ON imported_files
            WHEN OLD.sha256 = '${second.assetSha256}'
            BEGIN SELECT RAISE(ABORT, 'forced per-book failure'); END
        """.trimIndent())
        val deleted = library.deleteBook(first.bookId)
        val failed = runCatching { library.deleteBook(second.bookId) }.exceptionOrNull()
        assertNotNull(deleted)
        assertTrue(failed is DeleteFailure)
        assertEquals(second.bookId, library.library.first().single().book.bookId)
        assertTrue(File(files, "books/${second.assetSha256}.epub").isFile)
        assertEquals(1, library.bookmarks(second).first().size)
        assertEquals(second.bookId, library.history.first().single().book.bookId)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_delete")
        assertNotNull(library.deleteBook(second.bookId))
        assertTrue(library.library.first().isEmpty())
    }

    @Test
    fun nativeLocatorSurvivesDatabaseReopenAndRestoresMatchingChapter() = runBlocking {
        val book = add("Resume Test")
        val saved = library.openBook(book.bookId).use { opened ->
            val publication = opened.publication
            val chapter = checkNotNull(publication.locatorFromLink(publication.readingOrder[1]))
            val page = chapter.copy(locations = chapter.locations.copy(progression = 0.6, totalProgression = 0.8))
            val anchor = chapter.copy(text = Locator.Text(highlight = "The harbor lamp was visible."))
            SavedLocation(page, anchor, "portrait")
        }
        val encoded = saved.encode(book.assetSha256)
        assertEquals(saved, SavedLocation.decode(encoded, book.assetSha256))
        assertNull(SavedLocation.decode(encoded, "different digest"))
        assertNull(SavedLocation.decode("broken json", book.assetSha256))
        val future = JSONObject(encoded).put("version", 999).toString()
        assertNull(SavedLocation.decode(future, book.assetSha256))

        library.recordReading(book, 100, saved)
        database.close()
        openDatabase()
        library.openBook(book.bookId).use { opened ->
            val state = checkNotNull(opened.readingState)
            val decoded = SavedLocation.decode(state.locator, book.assetSha256)
            assertEquals(saved.locator, opened.publication.resumeLocator(decoded, "portrait", state.progression))
            assertEquals(saved.reflowAnchor, opened.publication.resumeLocator(decoded, "landscape", state.progression))
            assertNotNull(opened.publication.resumeLocator(null, "portrait", state.progression))
            assertNull(opened.publication.resumeLocator(null, "portrait", null))
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    @Test
    fun paginatedNavigatorTurnsForwardBackwardAndViaTocForBothChapterStructures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (sharedSpine in listOf(false, true)) {
            val title = if (sharedSpine) "Shared spine" else "Separate spine"
            val source = epub(title, sharedSpine)
            assertEquals(ImportResult.Added, library.importBook("$title.epub") { source.inputStream() })
            val book = library.library.first().single { it.book.title == title }.book
            val activity = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ReaderActivity
            try {
                library.openBook(book.bookId).use { opened ->
                    val publication = opened.publication
                    assertEquals(if (sharedSpine) 1 else 2, publication.readingOrder.size)
                    val firstResource = checkNotNull(publication.get(publication.readingOrder.first()))
                        .use { checkNotNull(it.read().getOrNull()).decodeToString() }
                    assertEquals(sharedSpine, firstResource.contains(":where([id=\"two\"]){break-before:column}"))
                    val factory = EpubNavigatorFactory(publication).createFragmentFactory(
                        initialLocator = null,
                        initialPreferences = EpubPreferences(scroll = false),
                    )
                    withContext(Dispatchers.Main) {
                        val navigator = withTimeout(10_000) {
                            activity.lifecycle.withResumed {
                                val host = FragmentContainerView(activity).apply { id = View.generateViewId() }
                                activity.setContentView(host)
                                activity.supportFragmentManager.fragmentFactory = factory
                                val fragment = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name)
                                    as EpubNavigatorFragment
                                activity.supportFragmentManager.commitNow { add(host.id, fragment) }
                                fragment
                            }
                        }
                        try {
                            awaitChapterPage(navigator, "First chapter")
                            val turns = ReaderPageTurns(navigator)
                            val viewport = navigator.publicationView
                            val center = TapEvent(PointF(viewport.width * 0.5f, viewport.height * 0.5f))
                            val next = center.copy(point = PointF(viewport.width * 0.85f, center.point.y))
                            val previous = center.copy(point = PointF(viewport.width * 0.15f, center.point.y))
                            assertFalse(turns.onTap(center))
                            assertTrue(turns.onTap(next))
                            awaitChapterPage(navigator, "Second chapter")
                            assertTrue(turns.onTap(previous))
                            awaitChapterPage(navigator, "First chapter")
                            assertTrue(navigator.go(publication.tableOfContents[1]))
                            awaitChapterPage(navigator, "Second chapter")
                        } finally {
                            activity.supportFragmentManager.commitNow(allowStateLoss = true) { remove(navigator) }
                        }
                    }
                }
                assertArrayEquals(source, File(files, "books/${book.assetSha256}.epub").readBytes())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun awaitChapterPage(navigator: EpubNavigatorFragment, expected: String) {
        val visibleHeadings = """
            [...document.querySelectorAll('h1')].filter(heading => {
                const rect = heading.getBoundingClientRect();
                return rect.left >= 0 && rect.left < innerWidth && rect.top >= 0 && rect.top < innerHeight;
            }).map(heading => heading.textContent).join(',');
        """.trimIndent()
        withTimeout(12_000) {
            while (navigator.evaluateJavascript(visibleHeadings) != JSONObject.quote(expected)) delay(80)
        }
    }

    @Test
    fun chapterBreaksPreserveAuthoredCssAndIgnoreUntypedNestedSections() {
        val input = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><style>#two{break-before:avoid}</style></head><body><article>
            <section id="one" epub:type="chapter"><h1>First</h1><p>A paragraph.</p></section>
            <section id="two" epub:type="chapter"><header><h1 id="title-two">Second</h1></header><p>Another paragraph.</p></section>
            <section id="aside"><h2>A subsection</h2><p>Unmarked text.</p></section>
            </article></body></html>
        """.trimIndent()
        val rendered = withChapterBreaks(input.toByteArray(), mapOf("one" to 1, "title-two" to 1, "aside" to 1))
            .decodeToString()
        assertTrue(rendered.contains(":where([id=\"two\"]){break-before:column}"))
        assertTrue(rendered.contains("</script><style>#two{break-before:avoid}</style>"))
        assertEquals(input, rendered.replace(Regex("<script>.*?</script>", RegexOption.DOT_MATCHES_ALL), ""))
        val untyped = input.replace(" epub:type=\"chapter\"", "")
        assertArrayEquals(untyped.toByteArray(), withChapterBreaks(untyped.toByteArray(), mapOf("one" to 1, "two" to 1)))
    }

    @Test
    fun historyUpdatesInPlaceAndSortsByMostRecentRead() = runBlocking {
        val first = add("First Book")
        val second = add("Second Book")
        assertTrue(library.history.first().isEmpty())
        library.recordReading(first, 100, null)
        library.recordReading(second, 200, null)
        assertEquals(second.bookId, library.recent.first().first().book.bookId)
        library.recordReading(first, 300, null)
        val history = library.history.first()
        assertEquals(listOf(first.bookId, second.bookId), history.map { it.book.bookId })
        assertEquals(listOf(300L, 200L), history.map { it.lastReadAt })
        assertEquals(first.bookId, library.recent.first().first().book.bookId)
    }

    @Test
    fun historyResetMakesBookUnreadInHomeAndHistoryAndPreservesTheImport() = runBlocking {
        val book = add("Reading Reset")
        val other = add("Other Reading")
        val location = secondChapter(book)
        val file = File(files, "books/${book.assetSha256}.epub")
        val cover = library.cover(book.assetSha256)
        val epubBytes = file.readBytes()
        val coverBytes = cover.readBytes()
        library.recordReading(other, 100, null)
        library.recordReading(book, 200, location)
        assertEquals(book.bookId, library.recent.first().first().book.bookId)

        assertNotNull(library.resetReading(book.bookId))
        assertEquals(listOf(other.bookId), library.history.first().map { it.book.bookId })
        assertEquals(listOf(other.bookId), library.recent.first().map { it.book.bookId })
        val inLibrary = library.library.first().single { it.book.bookId == book.bookId }
        assertEquals(book, inLibrary.book)
        assertTrue(inLibrary.available)
        assertEquals(0.0, inLibrary.progression)
        assertNull(inLibrary.lastReadAt)
        assertArrayEquals(epubBytes, file.readBytes())
        assertArrayEquals(coverBytes, cover.readBytes())
        val cleared = checkNotNull(database.libraryDao().readingState(book.bookId))
        assertNull(cleared.locator)
        assertNull(cleared.positionUpdatedAt)
        assertNull(cleared.section)

        library.recordReading(book, 250, location)
        assertEquals(cleared, database.libraryDao().readingState(book.bookId))
        library.openBook(book.bookId).use { opened ->
            assertNull(opened.readingState)
            assertNull(opened.publication.resumeLocator(null, "portrait", null))
        }
        library.recordReading(book, 300, null)
        assertEquals(listOf(book.bookId, other.bookId), library.history.first().map { it.book.bookId })
        assertEquals(300L, library.history.first().first().lastReadAt)
        assertNull(database.libraryDao().readingState(book.bookId)?.locator)
    }

    @Test
    fun undoRestoresTheEntireCheckpointAndOriginalOrdering() = runBlocking {
        val book = add("Restore Reading")
        val other = add("More Recent Reading")
        val location = secondChapter(book)
        library.recordReading(book, 100, location)
        library.recordReading(other, 200, null)
        val original = checkNotNull(database.libraryDao().readingState(book.bookId))

        val reset = checkNotNull(library.resetReading(book.bookId))
        library.undoReset(reset.copy(token = reset.token - 1))
        assertEquals(listOf(other.bookId), library.history.first().map { it.book.bookId })
        library.undoReset(reset)
        assertEquals(original, database.libraryDao().readingState(book.bookId))
        assertEquals(listOf(other.bookId, book.bookId), library.history.first().map { it.book.bookId })
        assertEquals(listOf(other.bookId, book.bookId), library.recent.first().map { it.book.bookId })
        library.openBook(book.bookId).use {
            assertEquals(location, SavedLocation.decode(it.readingState?.locator, book.assetSha256))
        }

        val earlier = checkNotNull(library.resetReading(book.bookId))
        library.recordReading(book, 300, null)
        library.undoReset(earlier)
        assertEquals(300L, library.history.first().first().lastReadAt)
        assertNull(database.libraryDao().readingState(book.bookId)?.locator)
        val latest = checkNotNull(library.resetReading(book.bookId))
        library.undoReset(earlier)
        assertEquals(listOf(other.bookId), library.history.first().map { it.book.bookId })
        library.undoReset(latest)
        assertEquals(300L, library.history.first().first().lastReadAt)
    }

    private suspend fun secondChapter(book: Book): SavedLocation = library.openBook(book.bookId).use { opened ->
        val chapter = checkNotNull(opened.publication.locatorFromLink(opened.publication.readingOrder[1]))
        SavedLocation(
            chapter.copy(
                title = "Second chapter",
                locations = chapter.locations.copy(progression = 0.6, totalProgression = 0.8),
            ),
        )
    }

    @Test
    fun homeLimitsToFiveAndHistoryGroupsByLocalDayAndSearches() = runBlocking {
        val zone = ZoneId.of("Asia/Kolkata")
        val times = listOf(
            "2026-11-22T17:00:00Z", "2026-11-22T17:30:00Z", "2026-11-22T18:00:00Z",
            "2026-11-22T18:20:00Z", "2026-11-22T18:35:00Z", "2026-11-22T19:00:00Z",
        )
        for ((index, time) in times.withIndex()) {
            val book = add("Volume ${index + 1}")
            library.recordReading(book, Instant.parse(time).toEpochMilli(), null)
        }
        val history = library.history.first()
        assertEquals(history.take(5), library.recent.first())
        val grouped = groupHistory(history, "", zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 22)), grouped.keys.toList())
        assertEquals(listOf(2, 4), grouped.values.map { it.size })
        assertEquals(history, grouped.values.flatten())
        assertEquals(listOf("Volume 3"), groupHistory(history, " VOLUME 3 ", zone).values.flatten().map { it.book.title })
        assertEquals(history, groupHistory(history, "test author", zone).values.flatten())
    }

    @Test
    fun bookmarksSurviveReopenAndHistoryResetAndSupportUndoAndCascade() = runBlocking {
        val book = add("Bookmarked book")
        val checkpoint = secondChapter(book)
        library.recordReading(book, 100, null)
        library.recordReading(book, 200, checkpoint)
        val marker = library.saveBookmark(book, checkpoint, "Second chapter", 1, 250)
        database.close()
        openDatabase()

        val stored = library.bookmarks(book).first().single()
        assertEquals(marker, stored.record)
        assertEquals(checkpoint.locator, stored.location.locator)
        val reset = checkNotNull(library.resetReading(book.bookId))
        assertEquals(marker, library.bookmarks(book).first().single().record)
        library.undoReset(reset)
        assertEquals(marker, library.bookmarks(book).first().single().record)

        assertTrue(library.removeBookmark(marker))
        assertTrue(library.bookmarks(book).first().isEmpty())
        assertEquals(marker, library.restoreBookmark(marker))
        assertEquals(marker, library.bookmarks(book).first().single().record)

        database.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE bookId = ?", arrayOf(book.bookId))
        assertTrue(library.bookmarks(book).first().isEmpty())
    }

    @Test
    fun bookmarkTextPointDeduplicatesAfterLayoutChangesAndKeepsDistinctPassages() = runBlocking {
        val book = add("Bookmark anchors")
        val native = secondChapter(book).locator
        fun location(offset: Int, progression: Double, layout: String): SavedLocation {
            val locator = native.copy(locations = native.locations.copy(progression = progression))
            val point = mapOf("cssSelector" to "body > :nth-child(2)", "textNodeIndex" to 0, "charOffset" to offset)
            val anchor = locator.copy(
                locations = locator.locations.copy(otherLocations = mapOf("domRange" to mapOf("start" to point))),
                text = Locator.Text(highlight = "The harbor lamp was visible."),
            )
            return SavedLocation(locator, anchor, layout)
        }
        val original = location(4, 0.41, "portrait")
        val reflowed = location(4, 0.32, "landscape")
        val first = async { library.saveBookmarkWithResult(book, original, "Second chapter", 1, 100) }
        val second = async { library.saveBookmarkWithResult(book, reflowed, "Second chapter", 1, 200) }
        val firstSave = first.await()
        val secondSave = second.await()
        assertEquals(firstSave.bookmark.bookmarkId, secondSave.bookmark.bookmarkId)
        assertTrue(firstSave.inserted xor secondSave.inserted)
        assertEquals(4, library.bookmarks(book).first().single().location.bookmarkPoint?.charOffset)
        assertTrue(original.locator.matchesBookmarkFallback(location(4, 0.41001, "portrait").locator))
        assertFalse(original.locator.matchesBookmarkFallback(reflowed.locator))

        val earlier = library.saveBookmark(book, location(1, 0.12, "portrait"), "Second chapter", 1, 300)
        assertEquals(listOf(earlier.bookmarkId, firstSave.bookmark.bookmarkId), library.bookmarks(book).first().map { it.record.bookmarkId })
    }

    @Test
    fun storageInspectionAccountsForFilesOnceAndPreservesRecoveryCandidates() = runBlocking {
        val book = add("Storage fixture")
        val missing = add("Missing storage fixture")
        val missingPayload = File(files, "books/${missing.assetSha256}.epub")
        assertTrue(missingPayload.delete())
        val recorded = secondChapter(book)
        library.recordReading(book, 100, recorded)
        library.saveBookmark(book, recorded, "Chapter", 1, 120)

        val orphanDigest = "c".repeat(64)
        fun fixture(relative: String, bytes: Int) = File(files, relative).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }
        val lost = fixture("books/$orphanDigest.epub", 40)
        val orphanedCover = fixture("book-covers/$orphanDigest.jpg", 30)
        val scratch = fixture("book-staging/book-123.part", 20)
        val dictionary = fixture("no_backup/dictionary/wordnet.sqlite", 55)
        val reading = fixture("databases/ebook-reader.db-wal", 60)
        val preferences = fixture("datastore/reader_appearance.preferences_pb", 15)
        val webCache = fixture("cache/active-webview", 25)
        val unexpected = File(files, "book-staging/book-456.part")
        Os.symlink(lost.path, unexpected.path)

        val paths = StoragePaths(
            files, File(files, "books"), File(files, "book-covers"), File(files, "book-staging"), File(files, "book-trash"),
            dictionary.parentFile!!, webCache.parentFile!!, File(files, "code_cache"), preferences.parentFile!!,
            File(reading.parentFile, "ebook-reader.db"),
        )
        val reopened = LibraryRepository(context, database.libraryDao(), files)
        reopened.recover()
        val storage = scanLibraryStorage(paths, database.libraryDao().storageCatalog(), emptySet(), true) {}
        assertEquals(2, storage.overview.importedBooks)
        assertEquals(1, storage.overview.booksOnDevice)
        assertEquals(55L, storage.overview.sizes[StorageCategory.Dictionary])
        assertEquals(75L, storage.overview.sizes[StorageCategory.ReadingData])
        assertEquals(45L, storage.overview.sizes[StorageCategory.Cache])
        assertEquals(20L, storage.overview.removableBytes)
        assertEquals(storage.overview.importedBytes + 40L, storage.overview.sizes[StorageCategory.Books])
        assertEquals(
            setOf(IntegrityIssueKind.Missing, IntegrityIssueKind.UnreferencedBooks, IntegrityIssueKind.OrphanedCovers,
                IntegrityIssueKind.AbandonedImports, IntegrityIssueKind.Unexpected),
            storage.issues.map { it.kind }.toSet(),
        )
        assertTrue(lost.isFile && orphanedCover.isFile && scratch.isFile)
        assertEquals(20L, reopened.clearStorageCache().bytes)
        assertFalse(scratch.exists())
        assertTrue(lost.isFile && orphanedCover.isFile && dictionary.isFile && webCache.isFile && unexpected.exists())
        assertEquals(100L, database.libraryDao().readingState(book.bookId)?.lastReadAt)
        assertEquals(1, reopened.bookmarks(book).first().size)
        assertNotNull(database.libraryDao().book(missing.bookId))
    }

    @Test
    fun concurrentImportScratchIsExcludedFromInspectionAndCleanup() = runBlocking {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = epub("Import in progress")
        val import = async {
            library.importBook("active.epub") {
                opened.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                source.inputStream()
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { opened.await(10, TimeUnit.SECONDS) })
            val storage = library.storageReport(checkIntegrity = true)
            assertEquals(0, storage.overview.removableFiles)
            assertTrue(storage.issues.none { it.kind == IntegrityIssueKind.AbandonedImports })
            assertEquals(0, library.clearStorageCache().removed)
        } finally {
            release.countDown()
        }
        assertEquals(ImportResult.Added, import.await())
        assertEquals("Import in progress", library.library.first().single().book.title)
    }

    @Test
    fun highlightsSurviveReopenRenameAndResetAndAreCascadeDeletedWithTheirBook() = runBlocking {
        val book = add("Saved passages")
        val quote = secondChapter(book).locator.copy(text = Locator.Text(
            before = "Before ", highlight = "a passage\nover two lines", after = " After.",
        ))
        library.recordReading(book, 100, SavedLocation(quote))
        assertTrue(library.saveHighlight(book.bookId, quote, "Second chapter", 1, HighlightColor.Blue, "Original note"))
        val original = library.highlights(book.bookId).first().single()
        database.close()
        openDatabase()
        assertEquals(original, library.highlights(book.bookId).first().single())

        val repaginated = quote.copy(locations = quote.locations.copy(progression = 0.2, fragments = listOf("page-break")))
        assertTrue(library.saveHighlight(book.bookId, repaginated, "Second chapter", 1, HighlightColor.Purple))
        assertFalse(library.saveHighlight(book.bookId, repaginated, "Second chapter", 1, HighlightColor.Purple))
        val recolored = library.highlights(book.bookId).first().single()
        assertEquals(original.record.highlightId, recolored.record.highlightId)
        assertEquals(original.locator, recolored.locator)
        assertEquals(HighlightColor.Purple, recolored.color)
        assertEquals("Original note", recolored.record.note)

        assertFalse(library.saveHighlight(book.bookId, repaginated, "Second chapter", 1, HighlightColor.Yellow, "Edited note"))
        val edited = library.highlights(book.bookId).first().single()
        assertEquals(HighlightColor.Purple, edited.color)
        assertEquals("Edited note", edited.record.note)
        assertTrue(library.setHighlightNote(book.bookId, edited.record.highlightId, null))
        assertNull(library.highlights(book.bookId).first().single().record.note)
        assertTrue(library.setHighlightNote(book.bookId, edited.record.highlightId, "Restored note"))

        assertEquals(RenameResult.Renamed, library.renameBook(book.bookId, "Renamed annotation fixture"))
        assertNotNull(library.resetReading(book.bookId))
        assertTrue(library.history.first().isEmpty())
        val afterReset = library.highlights(book.bookId).first().single()
        assertEquals("Restored note", afterReset.record.note)
        val removed = checkNotNull(library.removeHighlight(book.bookId, afterReset.record.highlightId))
        assertTrue(library.highlights(book.bookId).first().isEmpty())
        assertTrue(library.restoreHighlight(removed))
        assertEquals(afterReset, library.highlights(book.bookId).first().single())
        assertNotNull(library.deleteBook(book.bookId))
        assertTrue(library.highlights(book.bookId).first().isEmpty())
    }

    @Test
    fun v4MigrationPreservesExistingBooksReadingStateAndBookmarksBeforeAddingHighlights() = runBlocking {
        database.close()
        createLegacyDatabase(4) {
            val digest = "b".repeat(64)
            execSQL("INSERT INTO imported_files VALUES (?, 'epub', 10, 'PRESENT')", arrayOf(digest))
            execSQL(
                "INSERT INTO books VALUES ('legacy', ?, 'Existing title', '[]', NULL, NULL, NULL, 'Book.epub', 1)",
                arrayOf(digest),
            )
            execSQL("INSERT INTO reading_state VALUES ('legacy', 200, 150, 'legacy locator', 'Chapter', 0.5, NULL)")
            execSQL("INSERT INTO bookmarks VALUES ('marker','legacy','key','bookmark locator','Chapter',NULL,100,0,NULL,NULL)")
        }
        openDatabase()
        val book = library.history.first().single().book
        val dao = database.libraryDao()
        assertEquals("Book.epub", book.sourceName)
        assertEquals("legacy locator", dao.readingState("legacy")?.locator)
        assertEquals("marker", dao.bookmarks("legacy").first().single().bookmarkId)
        assertTrue(library.highlights("legacy").first().isEmpty())
        val locator = Locator(
            href = checkNotNull(Url("chapter.xhtml")), mediaType = MediaType.XHTML,
            text = Locator.Text(before = "Before ", highlight = "A saved passage", after = " after."),
        )
        library.saveHighlight("legacy", locator, "Chapter", 0, HighlightColor.Green)
        assertEquals("A saved passage", library.highlights("legacy").first().single().record.selectedText)
        assertEquals("marker", dao.bookmarks("legacy").first().single().bookmarkId)
    }

    @Test
    fun v1MigrationPreservesReadingStateAndSupportsBookmarks() = runBlocking {
        database.close()
        createLegacyDatabase(1) {
            val digest = "a".repeat(64)
            execSQL("INSERT INTO imported_files VALUES (?, 'epub', 10, 'PRESENT')", arrayOf(digest))
            execSQL(
                "INSERT INTO books VALUES ('existing', ?, 'Existing book', '[]', NULL, NULL, NULL, NULL, 1)",
                arrayOf(digest),
            )
            execSQL("INSERT INTO reading_state VALUES ('existing', 200, 150, 'original locator', 'Chapter', 0.5)")
        }
        openDatabase()
        val existingBook = library.history.first().single().book
        assertEquals("Existing book", existingBook.title)
        val state = checkNotNull(database.libraryDao().readingState("existing"))
        assertEquals("original locator", state.locator)
        assertEquals(150L, state.positionUpdatedAt)
        assertEquals(0.5, state.progression)
        assertNull(state.historyHiddenAt)
        assertTrue(library.bookmarks(existingBook).first().isEmpty())
        val locator = Locator(href = checkNotNull(Url("chapter.xhtml")), mediaType = MediaType.XHTML)
        val marker = library.saveBookmark(existingBook, SavedLocation(locator), "Chapter", 0, 300)
        assertEquals(marker, library.bookmarks(existingBook).first().single().record)
        assertEquals(state, database.libraryDao().readingState("existing"))
    }

    private fun createLegacyDatabase(version: Int, seed: SQLiteDatabase.() -> Unit) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = assets.open("${LibraryDatabase::class.java.name}/$version.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            val tables = schema.getJSONArray("entities")
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index)
                val tableName = table.getString("tableName")
                legacy.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = table.optJSONArray("indices")
                for (entry in 0 until (indices?.length() ?: 0)) {
                    val sql = requireNotNull(indices).getJSONObject(entry).getString("createSql")
                    legacy.execSQL(sql.replace("\${TABLE_NAME}", tableName))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
            legacy.seed()
            legacy.version = version
        }
    }

    @Test
    fun unreadableMissingAndCorruptFilesPreserveRecoverableState() = runBlocking {
        val broken = "Broken file".toByteArray()
        expect(BookProblem.Unsupported) { library.importBook("document.pdf") { broken.inputStream() } }
        expect(BookProblem.Corrupt) { library.importBook("broken.epub") { broken.inputStream() } }
        try {
            library.importBook("unreadable.epub") { throw IOException("Provider denied read") }
            fail("Expected an unreadable source failure")
        } catch (_: IOException) {
            assertTrue(library.library.first().isEmpty())
        }
        val content = epub("Recoverable Book")
        assertEquals(ImportResult.Added, library.importBook("original.epub") { content.inputStream() })
        val book = library.library.first().single().book
        library.recordReading(book, 100, null)
        val internal = File(files, "books/${book.assetSha256}.epub")
        assertTrue(internal.delete())
        expect(BookProblem.Missing) { library.openBook(book.bookId).close() }
        val missing = library.history.first().single()
        assertFalse(missing.available)
        assertEquals(100L, missing.lastReadAt)
        assertEquals(ImportResult.Relinked, library.importBook("renamed.epub") { content.inputStream() })
        internal.writeBytes(ByteArray(content.size))
        expect(BookProblem.Corrupt) { library.openBook(book.bookId).close() }
        assertEquals(ImportResult.Relinked, library.importBook("original.epub") { content.inputStream() })
        assertEquals(book.bookId, library.history.first().single().book.bookId)
        library.openBook(book.bookId).close()
    }

    private suspend fun add(title: String): Book {
        assertEquals(ImportResult.Added, library.importBook("$title.epub") { epub(title).inputStream() })
        return library.library.first().single { it.book.title == title }.book
    }

    private suspend fun expect(problem: BookProblem, operation: suspend () -> Unit) {
        try {
            operation()
            fail("Expected $problem")
        } catch (failure: BookFailure) {
            assertEquals(problem, failure.problem)
        }
    }

    private fun epub(title: String, sharedSpine: Boolean = false): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }

            entry("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray())
            val secondSpine = if (sharedSpine) "" else """<itemref idref="two"/>"""
            entry("book.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:ebookreader:test</dc:identifier>
                    <dc:title>$title</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
                  </manifest>
                  <spine><itemref idref="one"/>$secondSpine</spine>
                </package>
            """.trimIndent().toByteArray())
            val firstHref = if (sharedSpine) "one.xhtml#one" else "one.xhtml"
            val secondHref = if (sharedSpine) "one.xhtml#two" else "two.xhtml"
            entry("nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
                    <li><a href="$firstHref">First chapter</a></li>
                    <li><a href="$secondHref">Second chapter</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent().toByteArray())
            val firstResource = if (sharedSpine) """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>$title</title></head><body>
                <section id="one" epub:type="chapter"><h1>First chapter</h1><p>We sailed at dawn.</p></section>
                <section id="two" epub:type="chapter"><h1>Second chapter</h1><p>The harbor lamp was visible.</p></section>
                </body></html>
            """.trimIndent() else chapter("First chapter", "<p>We sailed at dawn.</p>")
            entry("one.xhtml", firstResource.toByteArray())
            entry("two.xhtml", chapter("Second chapter", "<p>The harbor lamp was visible.</p>").toByteArray())
            val image = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            val cover = ByteArrayOutputStream()
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, cover)
            image.recycle()
            entry("cover.png", cover.toByteArray())
        }
        return result.toByteArray()
    }

    private fun chapter(title: String, body: String) = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head><body><h1>$title</h1>$body</body>
        </html>
    """.trimIndent()
}
