package com.artemis.ebookreader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemis.ebookreader.R

private val previewShape = RoundedCornerShape(8.dp)
private val bookCardShape = RoundedCornerShape(5.dp)
private val coverShape = RoundedCornerShape(2.dp)
private val destinationIcons = listOf(R.drawable.ic_home, R.drawable.ic_book, R.drawable.ic_history, R.drawable.ic_settings)

@Composable
internal fun HomeThemePreview(colors: ColorScheme) {
    // The miniature is decorative. The card's accessible label uses the user's font scale.
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
        Column(
            Modifier.fillMaxWidth().height(162.dp).clip(previewShape)
                .background(colors.background).border(1.dp, colors.outlineVariant, previewShape)
                .clearAndSetSemantics { hideFromAccessibility() }.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PreviewHeading(stringResource(R.string.reading_now), colors.onBackground)
            Row(
                Modifier.fillMaxWidth().height(45.dp).clip(bookCardShape)
                    .background(colors.surfaceContainerHighest).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(24.dp).fillMaxHeight().clip(coverShape).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_book),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PlaceholderLine(colors.onSurface, 0.76f)
                    PlaceholderLine(colors.onSurfaceVariant, 0.48f)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.align(Alignment.End).clip(CircleShape).background(colors.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            stringResource(R.string.continue_book),
                            color = colors.onPrimary,
                            fontSize = 7.sp,
                            lineHeight = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
            PreviewHeading(stringResource(R.string.app_theme_preview_recently_read), colors.onBackground)
            repeat(2) { index ->
                Row(
                    Modifier.fillMaxWidth().height(18.dp).clip(bookCardShape)
                        .background(colors.surfaceContainerLow).padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(9.dp).fillMaxHeight().clip(coverShape)
                            .background(if (index == 0) colors.secondaryContainer else colors.tertiaryContainer),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PlaceholderLine(colors.onSurface, if (index == 0) 0.63f else 0.75f)
                        PlaceholderLine(colors.onSurfaceVariant, 0.40f)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().height(20.dp).clip(CircleShape)
                    .background(colors.surfaceContainerHigh).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((index, icon) in destinationIcons.withIndex()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val active = index == 0
                        Box(
                            Modifier.size(width = 22.dp, height = 14.dp).clip(CircleShape)
                                .background(if (active) colors.secondaryContainer else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(icon),
                                contentDescription = null,
                                modifier = Modifier.size(9.dp),
                                tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewHeading(label: String, color: Color) {
    Text(
        label,
        modifier = Modifier.height(12.dp),
        color = color,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun PlaceholderLine(color: Color, width: Float) {
    Box(Modifier.fillMaxWidth(width).height(2.dp).clip(CircleShape).background(color))
}
