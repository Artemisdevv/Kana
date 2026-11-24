package com.artemis.ebookreader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.artemis.ebookreader.data.preferences.ReaderAppearance
import com.artemis.ebookreader.data.preferences.ReaderAppearanceSettings
import com.artemis.ebookreader.data.preferences.ReaderTheme
import com.artemis.ebookreader.data.preferences.ReaderLayout
import com.artemis.ebookreader.data.preferences.ReaderFont
import com.artemis.ebookreader.data.preferences.ReaderAlignment
import com.artemis.ebookreader.data.preferences.ReaderWeight
import com.artemis.ebookreader.data.preferences.AppAppearanceMode
import com.artemis.ebookreader.data.preferences.ApplicationThemeSettings
import com.artemis.ebookreader.feature.reader.toNavigatorPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.ExperimentalReadiumApi

class ReaderAppearanceSettingsTest {
    @OptIn(ExperimentalReadiumApi::class)
    @Test
    fun mapsTypographyAndLayoutToReadiumWithoutEnablingNormalization() {
        val palette = ReaderTheme.WarmPaper.resolve(false)
        val layout = ReaderLayout(
            font = ReaderFont.OpenDyslexic, fontSize = 125, fontWeight = ReaderWeight.Semibold,
            scroll = true, alignment = ReaderAlignment.Justified, horizontalMargins = 130,
            lineHeight = 170, paragraphSpacing = 70, publisherStyles = false,
        )
        val native = layout.toNavigatorPreferences(palette, fontScale = 1.2)
        assertEquals(FontFamily.OPEN_DYSLEXIC, native.fontFamily)
        assertEquals(1.5, requireNotNull(native.fontSize), 0.001)
        assertEquals(1.5, requireNotNull(native.fontWeight), 0.001)
        assertEquals(1.3, requireNotNull(native.pageMargins), 0.001)
        assertEquals(true, native.scroll)
        assertEquals(1.7, requireNotNull(native.lineHeight), 0.001)
        assertEquals(0.7, requireNotNull(native.paragraphSpacing), 0.001)
        assertEquals(TextAlign.JUSTIFY, native.textAlign)
        assertEquals(palette.background, native.backgroundColor?.int)
        assertEquals(palette.text, native.textColor?.int)
        assertEquals(null, native.textNormalization)
    }
    @Test
    fun savedReadingLayoutIsGlobalValidatedAndResetLeavesLibraryPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val expected = ReaderLayout(
            font = ReaderFont.Monospace, fontSize = 135, fontWeight = ReaderWeight.Medium,
            lineHeight = 170, paragraphSpacing = 80, scroll = true, verticalMargins = 16,
            horizontalMargins = 130, alignment = ReaderAlignment.Left, publisherStyles = false,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fontKey = stringPreferencesKey("reader_font")
        val sizeKey = intPreferencesKey("reader_font_size_percent")
        val libraryKey = intPreferencesKey("library_grid_columns")
        try {
            withSettings(file) { settings ->
                settings.updateLayout { expected }
                settings.setTheme(ReaderTheme.WarmPaper)
            }
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val reader = ReaderAppearanceSettings(store, scope)
            val app = ApplicationThemeSettings(store, scope)
            withTimeout(5_000) {
                assertEquals(ReaderAppearance(ReaderTheme.WarmPaper, layout = expected), reader.appearance.filterNotNull().first())
                app.setMode(AppAppearanceMode.Light)
                assertEquals(AppAppearanceMode.Light, app.appearance.first { it.mode == AppAppearanceMode.Light }.mode)
                store.edit { it[fontKey] = "retired-font"; it[sizeKey] = -400; it[libraryKey] = 6 }
                assertEquals(expected.copy(font = ReaderFont.Publisher, fontSize = 100),
                    reader.appearance.filterNotNull().first { it.layout.fontSize == 100 }.layout)
                reader.reset()
                assertEquals(ReaderAppearance(), reader.appearance.filterNotNull().first { it.theme == ReaderTheme.FollowSystem })
                assertEquals(AppAppearanceMode.Light, app.appearance.value.mode)
                assertEquals(6, store.data.first()[libraryKey])
            }
        } finally {
            checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            file.delete()
        }
        Unit
    }
    @Test
    fun globalAppearanceAndPageNumbersSurviveADataStoreReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        try {
            withSettings(file) { settings ->
                assertEquals(ReaderAppearance(), settings.appearance.filterNotNull().first())
                settings.setTheme(ReaderTheme.WarmPaper)
                settings.setFullscreen(false)
                settings.setShowPageNumbers(false)
            }
            withSettings(file) { settings ->
                assertEquals(
                    ReaderAppearance(ReaderTheme.WarmPaper, fullscreen = false, showPageNumbers = false),
                    settings.appearance.filterNotNull().first(),
                )
                settings.setTheme(ReaderTheme.AmoledBlack)
                assertEquals(
                    ReaderAppearance(ReaderTheme.AmoledBlack, fullscreen = false, showPageNumbers = false),
                    settings.appearance.filterNotNull().first { it.theme == ReaderTheme.AmoledBlack },
                )
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun withSettings(file: File, block: suspend (ReaderAppearanceSettings) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            withTimeout(5000) { block(ReaderAppearanceSettings(store, scope)) }
        } finally {
            checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        }
    }
}
