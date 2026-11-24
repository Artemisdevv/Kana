package com.artemis.ebookreader.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderThemeTest {
    @Test
    fun explicitPalettesUseTheSpecifiedColorsInEitherSystemMode() {
        val expected = mapOf(
            ReaderTheme.SoftDark to ReaderPalette(0xFF303238.toInt(), 0xFFDDDCD7.toInt(), true),
            ReaderTheme.AmoledBlack to ReaderPalette(0xFF000000.toInt(), 0xFFF5F5F5.toInt(), true),
            ReaderTheme.ClassicLight to ReaderPalette(0xFFFFFFFF.toInt(), 0xFF242424.toInt(), false),
            ReaderTheme.WarmPaper to ReaderPalette(0xFFF3E8D2.toInt(), 0xFF3B3329.toInt(), false),
        )
        for ((theme, palette) in expected) {
            assertEquals(palette, theme.resolve(systemDark = true))
            assertEquals(palette, theme.resolve(systemDark = false))
        }
    }

    @Test
    fun readerDefaultsFollowSystemAndHideTheSystemBars() {
        val defaults = ReaderAppearance()
        assertTrue(defaults.fullscreen)
        assertEquals(ReaderTheme.FollowSystem, defaults.theme)
        assertEquals(ReaderTheme.SoftDark.resolve(true), defaults.theme.resolve(true))
        assertEquals(ReaderTheme.ClassicLight.resolve(false), defaults.theme.resolve(false))
    }
}
