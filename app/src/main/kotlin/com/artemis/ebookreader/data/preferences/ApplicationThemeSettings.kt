package com.artemis.ebookreader.data.preferences

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.artemis.ebookreader.R
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal enum class AppAppearanceMode(val key: String, @param:StringRes @get:StringRes val label: Int) {
    System("system", R.string.reader_follow_system),
    Light("light", R.string.app_theme_light),
    Dark("dark", R.string.app_theme_dark);

    fun isDark(systemDark: Boolean): Boolean = this == Dark || this == System && systemDark
}

internal enum class AppColorTheme(val key: String, @param:StringRes @get:StringRes val label: Int) {
    MaterialYou("material_you", R.string.app_color_material_you),
    Lavender("lavender", R.string.app_color_lavender),
    AppleGreen("apple_green", R.string.app_color_apple_green),
    Ocean("ocean", R.string.app_color_ocean),
    WarmSand("warm_sand", R.string.app_color_warm_sand),
    Rosewood("rosewood", R.string.app_color_rosewood),
    Graphite("graphite", R.string.app_color_graphite),
    Midnight("midnight", R.string.app_color_midnight),
}

internal data class AppAppearance(
    val mode: AppAppearanceMode = AppAppearanceMode.System,
    val colorTheme: AppColorTheme = AppColorTheme.MaterialYou,
    val amoledBlack: Boolean = false,
)

internal class ApplicationThemeSettings(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val appearance = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            val values = preferences.asMap()
            val colorThemeKey = values[ColorThemeKey]
            AppAppearance(
                mode = AppAppearanceMode.entries.find { it.key == values[ModeKey] } ?: AppAppearanceMode.System,
                colorTheme = if (colorThemeKey == "catppuccin") {
                    AppColorTheme.Lavender
                } else {
                    AppColorTheme.entries.find { it.key == colorThemeKey } ?: AppColorTheme.MaterialYou
                },
                amoledBlack = values[AmoledKey] == true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AppAppearance())

    suspend fun setMode(mode: AppAppearanceMode) {
        store.edit { it[ModeKey] = mode.key }
    }

    suspend fun setColorTheme(theme: AppColorTheme) {
        store.edit { it[ColorThemeKey] = theme.key }
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        store.edit { it[AmoledKey] = enabled }
    }

    private companion object {
        val ModeKey = stringPreferencesKey("app_theme")
        val ColorThemeKey = stringPreferencesKey("app_color_theme")
        val AmoledKey = booleanPreferencesKey("app_amoled_black")
    }
}
