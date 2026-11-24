package com.artemis.ebookreader.data.preferences

import com.artemis.ebookreader.feature.reader.effectivePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPresentationTest {
    @Test
    fun defaultsUsePublisherTypographyAndPaginatedLayout() {
        val preferences = ReaderLayout()
        assertEquals(100, preferences.fontSize)
        assertTrue(preferences.publisherStyles)
        assertTrue(!preferences.scroll)
        assertEquals(ReaderFont.Publisher, preferences.font)
        assertEquals(ReaderWeight.Publisher, preferences.fontWeight)
        assertNull(preferences.lineHeight)
        assertNull(preferences.paragraphSpacing)
        assertEquals(ReaderAlignment.Publisher, preferences.alignment)
    }

    @Test
    fun publisherSwitchOnlyDisablesTheOverridesThatRequireIt() {
        val layout = ReaderLayout(
            font = ReaderFont.OpenDyslexic, fontSize = 125, fontWeight = ReaderWeight.Semibold,
            scroll = true, alignment = ReaderAlignment.Justified, horizontalMargins = 130,
            lineHeight = 170, paragraphSpacing = 70,
        )
        assertEquals(layout.copy(lineHeight = null, paragraphSpacing = null, alignment = ReaderAlignment.Publisher),
            layout.effectivePresentation())
        val custom = layout.copy(publisherStyles = false)
        assertEquals(custom, custom.effectivePresentation())
    }

    @Test
    fun sliderScalesKeepDefaultsAndStepsWithinBounds() {
        for (scale in ReaderScale.entries) {
            assertTrue(scale.accepts(scale.default))
            assertTrue(scale.accepts(scale.min))
            assertTrue(scale.accepts(scale.max))
            assertTrue(!scale.accepts(scale.min - 1))
            assertTrue(!scale.accepts(scale.min + 1))
        }
    }
}
