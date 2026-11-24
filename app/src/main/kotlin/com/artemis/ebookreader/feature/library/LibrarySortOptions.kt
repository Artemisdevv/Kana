package com.artemis.ebookreader.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.BookDisplayName
import com.artemis.ebookreader.data.preferences.LibrarySortBy
import com.artemis.ebookreader.data.preferences.LibrarySortOrder

@Composable
internal fun LibrarySortButton(library: LibraryViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.library_sort))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(304.dp),
        ) {
            LibrarySortOptions(library)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySortOptions(library: LibraryViewModel) {
    val loaded by library.organization.collectAsStateWithLifecycle()
    val preferences = loaded ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SortHeading(R.string.library_display_name)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BookDisplayName.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = preferences.displayName == mode,
                    onClick = { library.setDisplayName(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, BookDisplayName.entries.size),
                ) {
                    Text(stringResource(if (mode == BookDisplayName.Filename) R.string.library_name_filename else R.string.library_name_title))
                }
            }
        }
        SortCaption(if (preferences.displayName == BookDisplayName.Filename) R.string.library_name_filename_hint else R.string.library_name_title_hint)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SortHeading(R.string.library_sort_by)
        Column(Modifier.selectableGroup()) {
            for (sort in LibrarySortBy.entries) {
                val selected = preferences.sortBy == sort
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected, role = Role.RadioButton) { library.setSortBy(sort) }
                        .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(stringResource(sort.label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SortHeading(R.string.settings_library_sort)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LibrarySortOrder.entries.forEachIndexed { index, order ->
                SegmentedButton(
                    selected = preferences.sortOrder == order,
                    onClick = { library.setSortOrder(order) },
                    shape = SegmentedButtonDefaults.itemShape(index, LibrarySortOrder.entries.size),
                ) {
                    Text(stringResource(if (order == LibrarySortOrder.Ascending) R.string.library_sort_ascending else R.string.library_sort_descending))
                }
            }
        }
        val ascending = preferences.sortOrder == LibrarySortOrder.Ascending
        SortCaption(
            when (preferences.sortBy) {
                LibrarySortBy.Name, LibrarySortBy.Author -> if (ascending) R.string.library_sort_az else R.string.library_sort_za
                LibrarySortBy.DateImported, LibrarySortBy.LastRead -> if (ascending) R.string.library_sort_oldest else R.string.library_sort_newest
                LibrarySortBy.FileSize -> if (ascending) R.string.library_sort_smallest else R.string.library_sort_largest
            },
        )
    }
}

private val LibrarySortBy.label: Int
    get() = when (this) {
        LibrarySortBy.Name -> R.string.library_sort_name
        LibrarySortBy.Author -> R.string.library_sort_author
        LibrarySortBy.DateImported -> R.string.library_sort_date_imported
        LibrarySortBy.LastRead -> R.string.library_sort_last_read
        LibrarySortBy.FileSize -> R.string.library_sort_file_size
    }

@Composable
private fun SortHeading(@StringRes title: Int) {
    Text(
        stringResource(title),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun SortCaption(@StringRes caption: Int) {
    Text(
        stringResource(caption),
        Modifier.padding(top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
