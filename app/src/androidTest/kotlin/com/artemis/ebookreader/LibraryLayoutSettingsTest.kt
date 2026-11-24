package com.artemis.ebookreader

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.app.MainActivity
import com.artemis.ebookreader.data.library.Book
import com.artemis.ebookreader.data.library.ImportedFile
import com.artemis.ebookreader.data.library.LibraryDatabase
import com.artemis.ebookreader.data.library.LibraryRepository
import com.artemis.ebookreader.data.preferences.LibraryLayout
import com.artemis.ebookreader.data.preferences.LibraryLayoutSettings
import com.artemis.ebookreader.data.preferences.LibraryViewMode
import com.artemis.ebookreader.feature.library.LibraryScreen
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.ui.theme.EBookReaderTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutSettingsTest {
    @Test
    fun defaultGridAndAllColumnCountsPersistAcrossListAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        try {
            withSettings(file) { settings ->
                assertEquals(LibraryLayout(LibraryViewMode.Grid, 3), settings.layout.value)
                for (columns in LibraryLayout.ColumnCounts) {
                    settings.setColumns(columns)
                    assertEquals(LibraryLayout(LibraryViewMode.Grid, columns), settings.layout.first { it.columns == columns })
                }
                settings.setMode(LibraryViewMode.List)
                assertEquals(6, settings.layout.first { it.mode == LibraryViewMode.List }.columns)
            }
            withSettings(file) { settings ->
                assertEquals(6, settings.layout.first { it.mode == LibraryViewMode.List }.columns)
                settings.setMode(LibraryViewMode.Grid)
                assertEquals(6, settings.layout.first { it.mode == LibraryViewMode.Grid }.columns)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun compactGridFitsFilenameFallbackAndKeepsCoverTappable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val files = File(context.cacheDir, UUID.randomUUID().toString())
        val filename = "A very long filename used when this EPUB does not provide a title.epub"
        val shortTitle = "Sea"
        try {
            val dao = database.libraryDao()
            for ((index, title) in listOf("", shortTitle).withIndex()) {
                val digest = index.toString().repeat(64)
                dao.addToLibrary(
                    ImportedFile(digest, "epub", 10, "PRESENT"),
                    Book("grid-$index", digest, title, "[]", null, null, null, filename, 0),
                )
            }
            withSettings(file) { settings ->
                val library = LibraryViewModel(LibraryRepository(context, dao, files), settings)
                val opened = AtomicReference<String>()
                val activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ) as MainActivity
                try {
                    instrumentation.runOnMainSync {
                        activity.setContent {
                            EBookReaderTheme { LibraryScreen(library, LibraryLayout(columns = 6), 80.dp) { opened.set(it) } }
                        }
                    }
                    val longBounds = awaitTitle(automation::getRootInActiveWindow, filename.removeSuffix(".epub"))
                    val shortBounds = awaitTitle(automation::getRootInActiveWindow, shortTitle)
                    assertEquals(shortBounds.top, longBounds.top)
                    assertEquals(shortBounds.height(), longBounds.height())
                    assertTrue(longBounds.right <= shortBounds.left || shortBounds.right <= longBounds.left)

                    val density = activity.resources.displayMetrics.density
                    assertTrue(longBounds.height() in (25 * density).toInt()..(40 * density).toInt())
                    val coverX = longBounds.centerX()
                    val coverY = longBounds.top - (longBounds.width() * 0.75f + 5 * density).toInt()
                    val screenshot = checkNotNull(automation.takeScreenshot())
                    try {
                        val background = screenshot.getPixel(coverX - longBounds.width() / 3, coverY)
                        assertTrue((-8..8).any { dx ->
                            (-8..8).any { dy -> screenshot.getPixel(coverX + dx, coverY + dy) != background }
                        })
                    } finally {
                        screenshot.recycle()
                    }
                    automation.executeShellCommand("input tap $coverX $coverY").close()
                    withTimeout(5_000) { while (opened.get() == null) delay(80) }
                    assertEquals("grid-0", opened.get())
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            database.close()
            file.delete()
        }
    }

    private suspend fun awaitTitle(root: () -> AccessibilityNodeInfo?, title: String): Rect = withTimeout(5_000) {
        var node = root()?.findTitle(title)
        while (node == null) {
            delay(80)
            node = root()?.findTitle(title)
        }
        Rect().also(node::getBoundsInScreen)
    }

    private fun AccessibilityNodeInfo.findTitle(title: String): AccessibilityNodeInfo? {
        if (text?.toString() == title) return this
        for (index in 0 until childCount) {
            getChild(index)?.findTitle(title)?.let { return it }
        }
        return null
    }

    private suspend fun withSettings(file: File, block: suspend (LibraryLayoutSettings) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            withTimeout(5_000) { block(LibraryLayoutSettings(store, scope)) }
        } finally {
            job.cancelAndJoin()
        }
    }
}
