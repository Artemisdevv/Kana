package com.artemis.ebookreader.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.preferences.LibraryLayout
import com.artemis.ebookreader.data.preferences.LibraryViewMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryLayoutSheet(
    layout: LibraryLayout,
    onModeChange: (LibraryViewMode) -> Unit,
    onColumnsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    wide: Boolean,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.library_layout),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            LibraryLayoutOptions(layout, onModeChange, onColumnsChange, wide = wide)
        }
    }
}

@Composable
internal fun LibraryLayoutOptions(
    layout: LibraryLayout,
    onModeChange: (LibraryViewMode) -> Unit,
    onColumnsChange: (Int) -> Unit,
    showDividers: Boolean = false,
    wide: Boolean = false,
) {
    Column(Modifier.selectableGroup()) {
        for ((index, mode) in LibraryViewMode.entries.withIndex()) {
            if (showDividers && index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            val selected = layout.mode == mode
            val label = if (mode == LibraryViewMode.Grid) R.string.library_grid_view else R.string.library_list_view
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { RadioButton(selected = selected, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.selectable(selected, role = Role.RadioButton) { onModeChange(mode) },
            )
        }
    }
    if (layout.mode == LibraryViewMode.Grid) {
        if (showDividers) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        val range = LibraryLayout.ColumnCounts
        val sliderLabel = stringResource(R.string.library_grid_columns_label)
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(pluralStringResource(R.plurals.library_grid_columns_value, layout.columns, layout.columns))
            Slider(
                value = layout.columns.toFloat(),
                onValueChange = { onColumnsChange(it.roundToInt()) },
                modifier = Modifier.semantics { contentDescription = sliderLabel },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
            )
            if (wide) {
                Text(
                    stringResource(R.string.library_grid_adaptive_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
