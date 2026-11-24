package com.artemis.ebookreader.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal enum class LibraryViewMode(val key: String) { Grid("grid"), List("list") }

internal enum class BookDisplayName(val key: String) { Filename("filename"), BookTitle("book_title") }

internal enum class LibrarySortBy(val key: String) {
    Name("name"), Author("author"), DateImported("imported"), LastRead("last_read"), FileSize("file_size"),
}

internal enum class LibrarySortOrder(val key: String) { Ascending("ascending"), Descending("descending") }

internal data class LibraryOrganization(
    val displayName: BookDisplayName = BookDisplayName.Filename,
    val sortBy: LibrarySortBy = LibrarySortBy.Name,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.Ascending,
)

internal data class LibraryLayout(
    val mode: LibraryViewMode = LibraryViewMode.Grid,
    val columns: Int = 3,
) {
    companion object {
        val ColumnCounts = 2..6
    }
}

internal class LibraryLayoutSettings(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    private val preferences = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    val layout = preferences
        .map { preferences ->
            val defaults = LibraryLayout()
            LibraryLayout(
                mode = LibraryViewMode.entries.find { it.key == preferences[ViewMode] } ?: defaults.mode,
                columns = preferences[Columns]?.takeIf { it in LibraryLayout.ColumnCounts } ?: defaults.columns,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = LibraryLayout())

    val organization = preferences
        .map { values ->
            val defaults = LibraryOrganization()
            LibraryOrganization(
                displayName = BookDisplayName.entries.find { it.key == values[DisplayName] } ?: defaults.displayName,
                sortBy = LibrarySortBy.entries.find { it.key == values[SortBy] } ?: defaults.sortBy,
                sortOrder = LibrarySortOrder.entries.find { it.key == values[SortOrder] } ?: defaults.sortOrder,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    suspend fun setDisplayName(mode: BookDisplayName) {
        store.edit { it[DisplayName] = mode.key }
    }

    suspend fun setSortBy(sortBy: LibrarySortBy) {
        store.edit { it[SortBy] = sortBy.key }
    }

    suspend fun setSortOrder(order: LibrarySortOrder) {
        store.edit { it[SortOrder] = order.key }
    }

    suspend fun setMode(mode: LibraryViewMode) {
        store.edit { it[ViewMode] = mode.key }
    }

    suspend fun setColumns(columns: Int) {
        require(columns in LibraryLayout.ColumnCounts)
        store.edit { it[Columns] = columns }
    }

    private companion object {
        val ViewMode = stringPreferencesKey("library_view_mode")
        val Columns = intPreferencesKey("library_grid_columns")
        val DisplayName = stringPreferencesKey("library_book_display_name")
        val SortBy = stringPreferencesKey("library_sort_by")
        val SortOrder = stringPreferencesKey("library_sort_order")
    }
}
