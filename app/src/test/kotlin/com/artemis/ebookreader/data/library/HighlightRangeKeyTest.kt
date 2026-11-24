package com.artemis.ebookreader.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HighlightRangeKeyTest {
    @Test
    fun anchorsIdentifyQuotesByResourceAndBothSidesOfTheirContext() {
        fun key(resource: String = "chapter.xhtml", before: String = "first ", selection: String = "same phrase", after: String = " last") =
            highlightRangeKey(resource, before, selection, after)

        assertEquals(key(), key())
        assertNotEquals(key(), key(before = "second "))
        assertNotEquals(key(), key(after = " later"))
        assertNotEquals(key(), key(resource = "other.xhtml"))
        assertNotEquals(key(), key(selection = "same"))
        assertNotEquals(highlightRangeKey("a", "b:c", "d", ""), highlightRangeKey("a", "b", "c:d", ""))
    }

    @Test
    fun storesStableIdentifiersForTheApprovedSwatches() {
        assertEquals(listOf("yellow", "green", "blue", "pink", "purple"), HighlightColor.entries.map { it.key })
        assertEquals(
            listOf(0xFFE4C65D, 0xFF83B88A, 0xFF83A8DF, 0xFFD68DA7, 0xFFB49AD9),
            HighlightColor.entries.map { it.argb.toUInt().toLong() },
        )
        assertEquals(HighlightColor.Yellow, HighlightColor.fromKey("future-color"))
    }
}
