package com.artemis.ebookreader.feature.reader

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.HighlightColor
import com.artemis.ebookreader.data.library.MaxNoteLength

internal data class ReaderMenuAction(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val invoke: () -> Unit,
)

@StringRes
internal fun HighlightColor.label(): Int = when (this) {
    HighlightColor.Yellow -> R.string.highlight_yellow
    HighlightColor.Green -> R.string.highlight_green
    HighlightColor.Blue -> R.string.highlight_blue
    HighlightColor.Pink -> R.string.highlight_pink
    HighlightColor.Purple -> R.string.highlight_purple
}

@Composable
internal fun ReaderHighlightMenu(
    anchor: ReaderPopupAnchor,
    selected: HighlightColor?,
    actions: List<ReaderMenuAction>,
    onColor: (HighlightColor) -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val position = remember(anchor, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val edge = with(density) { 8.dp.roundToPx() }
                val gap = with(density) { 16.dp.roundToPx() }
                val safe = anchor.safeArea
                val left = safe.left + edge
                val top = safe.top + edge
                val right = (safe.right - edge).coerceAtLeast(left)
                val bottom = (safe.bottom - edge).coerceAtLeast(top)
                val passage = anchor.passage
                val above = passage.top - gap - popupContentSize.height
                val below = passage.bottom + gap
                val y = when {
                    above >= top -> above
                    below + popupContentSize.height <= bottom -> below
                    passage.top - top >= bottom - passage.bottom -> above
                    else -> below
                }
                return IntOffset(
                    (passage.centerX() - popupContentSize.width / 2).coerceIn(left, (right - popupContentSize.width).coerceAtLeast(left)),
                    y.coerceIn(top, (bottom - popupContentSize.height).coerceAtLeast(top)),
                )
            }
        }
    }
    val width = with(density) { (anchor.safeArea.width().toDp() - 16.dp).coerceIn(1.dp, 288.dp) }
    Popup(
        position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = onDismiss != null),
    ) {
        val colors = MaterialTheme.colorScheme
        Surface(
            Modifier.width(width),
            color = colors.surfaceContainerHighest,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    for (color in HighlightColor.entries) {
                        val label = stringResource(color.label())
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .selectable(color == selected, role = Role.RadioButton) { onColor(color) }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            val swatch = Modifier.size(26.dp).background(Color(color.argb), CircleShape)
                            Box(
                                if (color == selected) swatch.border(2.dp, colors.onSurface, CircleShape) else swatch,
                                contentAlignment = Alignment.Center,
                            ) {
                                if (color == selected) {
                                    Icon(painterResource(R.drawable.ic_book_checked), null, Modifier.size(17.dp), tint = Color(0xFF241E16))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = colors.onSurface.copy(alpha = 0.16f))
                Row(Modifier.fillMaxWidth()) {
                    for (action in actions) {
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = action.invoke).padding(horizontal = 3.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(painterResource(action.icon), null, Modifier.size(17.dp))
                            Text(
                                stringResource(action.label),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderNoteEditor(
    passage: String,
    chapter: String?,
    note: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemoveNote: (() -> Unit)?,
) {
    var value by remember { mutableStateOf(note.orEmpty()) }
    val valid = value.isNotBlank() && value.trim().length <= MaxNoteLength
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        title = { ReaderPopupTitle(if (note == null) R.string.highlight_add_note else R.string.highlight_edit_note) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                chapter?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(passage, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.highlight_note)) },
                    minLines = 3,
                    maxLines = 7,
                    isError = value.trim().length > MaxNoteLength,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.trim().length > MaxNoteLength) Text(stringResource(R.string.highlight_note_too_long))
                if (onRemoveNote != null) {
                    TextButton(
                        onClick = onRemoveNote,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text(stringResource(R.string.highlight_remove_note)) }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = valid) { Text(stringResource(R.string.highlight_save_note)) } },
    )
}
