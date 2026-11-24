package com.artemis.ebookreader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionTest {
    @Test
    fun togglesStableBookIdsAcrossChangingOrder() {
        val selected = LibrarySelection(1, setOf("book-b"))
        val reordered = linkedSetOf("book-c", "book-b", "book-a")
        assertEquals(setOf("book-b", "book-a"), selected.toggle("book-a", reordered).ids)
        assertTrue(selected.toggle("book-b", reordered).ids.isEmpty())
    }

    @Test
    fun selectingAllUsesOnlyAvailableSearchResultsAndTogglesThemOff() {
        val selection = LibrarySelection(1, setOf("match-one", "hidden", "unavailable"))
        val visible = setOf("match-one", "match-two")
        val selected = selection.toggleAll(visible)
        assertEquals(visible, selected.ids)
        assertTrue(selected.toggleAll(visible).ids.isEmpty())
        assertTrue(selected.toggleAll(emptySet()).ids.isEmpty())
    }

    @Test
    fun filtersDiscardHiddenUnavailableAndRemovedBooksWithoutEndingTheSession() {
        val selection = LibrarySelection(1, setOf("valid", "hidden", "deleted", "unavailable"))
        val restricted = selection.visible(setOf("valid", "other"))
        assertEquals(LibrarySelection(1, setOf("valid")), restricted)
        assertEquals(restricted, restricted.toggle("deleted", setOf("valid", "other")))
        assertEquals(restricted, restricted.visible(setOf("valid", "hidden")))
        assertEquals(LibrarySelection(1, emptySet()), restricted.visible(emptySet()))
    }
}
