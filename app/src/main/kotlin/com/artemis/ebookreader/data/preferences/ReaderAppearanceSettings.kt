package com.artemis.ebookreader.data.preferences

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.artemis.ebookreader.R
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class ReaderPalette(val background: Int, val text: Int, val dark: Boolean)

internal enum class ReaderTheme(
    val key: String,
    @param:StringRes @get:StringRes val label: Int,
    private val palette: ReaderPalette? = null,
) {
    FollowSystem("system", R.string.reader_follow_system),
    SoftDark("soft-dark", R.string.reader_soft_dark, ReaderPalette(0xFF303238.toInt(), 0xFFDDDCD7.toInt(), true)),
    AmoledBlack("amoled-black", R.string.reader_amoled_black, ReaderPalette(0xFF000000.toInt(), 0xFFF5F5F5.toInt(), true)),
    ClassicLight("classic-light", R.string.reader_classic_light, ReaderPalette(0xFFFFFFFF.toInt(), 0xFF242424.toInt(), false)),
    WarmPaper("warm-paper", R.string.reader_warm_paper, ReaderPalette(0xFFF3E8D2.toInt(), 0xFF3B3329.toInt(), false));

    fun resolve(systemDark: Boolean): ReaderPalette =
        palette ?: checkNotNull(if (systemDark) SoftDark.palette else ClassicLight.palette)
}

internal data class ReaderAppearance(
    val theme: ReaderTheme = ReaderTheme.FollowSystem,
    val fullscreen: Boolean = true,
    val layout: ReaderLayout = ReaderLayout(),
    val showPageNumbers: Boolean = true,
    val keepScreenAwake: Boolean = true,
)

internal data class BundledReaderFont(
    val family: String,
    val directory: String,
    val weightRange: IntRange? = null,
) {
    val assetPath: String get() = "reader-fonts/$directory"
}

internal enum class ReaderFont(
    @param:StringRes @get:StringRes val label: Int,
    val bundled: BundledReaderFont? = null,
) {
    Publisher(R.string.reader_publisher_default),
    Serif(R.string.reader_serif),
    SansSerif(R.string.reader_sans_serif),
    Monospace(R.string.reader_monospace),
    AccessibleDfA(R.string.reader_accessible_dfa),
    Duospace(R.string.reader_duospace),
    OpenDyslexic(R.string.reader_open_dyslexic),
    Literata(R.string.reader_literata, BundledReaderFont("Literata", "literata", 200..900)),
    Lora(R.string.reader_lora, BundledReaderFont("Lora", "lora", 400..700)),
    Inter(R.string.reader_inter, BundledReaderFont("Inter", "inter", 100..900)),
    AtkinsonHyperlegible(
        R.string.reader_atkinson_hyperlegible,
        BundledReaderFont("Atkinson Hyperlegible", "atkinsonhyperlegible"),
    ),
    JetBrainsMono(R.string.reader_jetbrains_mono, BundledReaderFont("JetBrains Mono", "jetbrainsmono", 100..800)),
}
internal enum class ReaderWeight { Publisher, Regular, Medium, Semibold }
internal enum class ReaderAlignment { Publisher, Left, Justified }

internal enum class ReaderScale(val min: Int, val max: Int, val step: Int, val default: Int) {
    FontSize(70, 200, 5, 100),
    LineHeight(100, 220, 10, 150),
    ParagraphSpacing(0, 150, 10, 50),
    HorizontalMargins(50, 200, 10, 100),
    VerticalMargins(0, 40, 4, 0);

    fun accepts(value: Int): Boolean = value in min..max && (value - min) % step == 0
}

internal data class ReaderLayout(
    val font: ReaderFont = ReaderFont.Publisher,
    val fontSize: Int = ReaderScale.FontSize.default,
    val fontWeight: ReaderWeight = ReaderWeight.Publisher,
    val lineHeight: Int? = null,
    val paragraphSpacing: Int? = null,
    val scroll: Boolean = false,
    val horizontalMargins: Int = ReaderScale.HorizontalMargins.default,
    val verticalMargins: Int = ReaderScale.VerticalMargins.default,
    val alignment: ReaderAlignment = ReaderAlignment.Publisher,
    val publisherStyles: Boolean = true,
)

internal val Context.readerAppearanceStore by preferencesDataStore(name = "reader_appearance")

internal class ReaderAppearanceSettings(store: DataStore<Preferences>, scope: CoroutineScope) {
    private val store = store
    val appearance = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    suspend fun setTheme(theme: ReaderTheme) {
        store.edit { it[ThemeKey] = theme.key }
    }

    suspend fun setFullscreen(enabled: Boolean) {
        store.edit { it[FullscreenKey] = enabled }
    }

    suspend fun setShowPageNumbers(enabled: Boolean) {
        store.edit { it[PageNumbersKey] = enabled }
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        store.edit { it[KeepScreenAwakeKey] = enabled }
    }

    suspend fun updateLayout(transform: (ReaderLayout) -> ReaderLayout) {
        store.edit { preferences -> preferences.writeLayout(transform(read(preferences).layout)) }
    }

    suspend fun reset() {
        store.edit { preferences -> ReaderKeys.forEach { preferences.remove(it) } }
    }

    private fun read(prefs: Preferences): ReaderAppearance {
        val values = prefs.asMap()
        return ReaderAppearance(
            theme = ReaderTheme.entries.find { it.key == values[ThemeKey] } ?: ReaderTheme.FollowSystem,
            fullscreen = values[FullscreenKey] as? Boolean ?: true,
            showPageNumbers = values[PageNumbersKey] as? Boolean ?: true,
            keepScreenAwake = values[KeepScreenAwakeKey] as? Boolean ?: true,
            layout = ReaderLayout(
                font = ReaderFont.entries.find { it.name == values[FontKey] } ?: ReaderFont.Publisher,
                fontSize = (values[FontSizeKey] as? Int)?.takeIf(ReaderScale.FontSize::accepts) ?: ReaderScale.FontSize.default,
                fontWeight = ReaderWeight.entries.find { it.name == values[WeightKey] } ?: ReaderWeight.Publisher,
                lineHeight = (values[LineHeightKey] as? Int)?.takeIf(ReaderScale.LineHeight::accepts),
                paragraphSpacing = (values[ParagraphKey] as? Int)?.takeIf(ReaderScale.ParagraphSpacing::accepts),
                scroll = values[ScrollKey] as? Boolean ?: false,
                horizontalMargins = (values[HorizontalKey] as? Int)?.takeIf(ReaderScale.HorizontalMargins::accepts)
                    ?: ReaderScale.HorizontalMargins.default,
                verticalMargins = (values[VerticalKey] as? Int)?.takeIf(ReaderScale.VerticalMargins::accepts)
                    ?: ReaderScale.VerticalMargins.default,
                alignment = ReaderAlignment.entries.find { it.name == values[AlignmentKey] } ?: ReaderAlignment.Publisher,
                publisherStyles = values[PublisherKey] as? Boolean ?: true,
            ),
        )
    }

    private fun MutablePreferences.writeLayout(layout: ReaderLayout) {
        this[FontKey] = layout.font.name
        this[FontSizeKey] = layout.fontSize
        this[WeightKey] = layout.fontWeight.name
        layout.lineHeight?.let { this[LineHeightKey] = it } ?: remove(LineHeightKey)
        layout.paragraphSpacing?.let { this[ParagraphKey] = it } ?: remove(ParagraphKey)
        this[ScrollKey] = layout.scroll
        this[HorizontalKey] = layout.horizontalMargins
        this[VerticalKey] = layout.verticalMargins
        this[AlignmentKey] = layout.alignment.name
        this[PublisherKey] = layout.publisherStyles
    }

    private companion object {
        val ThemeKey = stringPreferencesKey("reader_theme")
        val FullscreenKey = booleanPreferencesKey("fullscreen_reading")
        val PageNumbersKey = booleanPreferencesKey("reader_show_page_numbers")
        val KeepScreenAwakeKey = booleanPreferencesKey("reader_keep_screen_awake")
        val FontKey = stringPreferencesKey("reader_font")
        val FontSizeKey = intPreferencesKey("reader_font_size_percent")
        val WeightKey = stringPreferencesKey("reader_font_weight")
        val LineHeightKey = intPreferencesKey("reader_line_height_percent")
        val ParagraphKey = intPreferencesKey("reader_paragraph_spacing_percent")
        val ScrollKey = booleanPreferencesKey("reader_scroll")
        val HorizontalKey = intPreferencesKey("reader_horizontal_margin_percent")
        val VerticalKey = intPreferencesKey("reader_vertical_margin_dp")
        val AlignmentKey = stringPreferencesKey("reader_alignment")
        val PublisherKey = booleanPreferencesKey("reader_publisher_styles")
        val ReaderKeys: List<Preferences.Key<*>> = listOf(
            ThemeKey, FullscreenKey, PageNumbersKey, FontKey, FontSizeKey, WeightKey, LineHeightKey,
            ParagraphKey, ScrollKey, HorizontalKey, VerticalKey, AlignmentKey, PublisherKey, KeepScreenAwakeKey,
        )
    }
}
