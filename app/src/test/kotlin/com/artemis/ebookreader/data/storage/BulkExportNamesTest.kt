package com.artemis.ebookreader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkExportNamesTest {
    @Test
    fun destinationAndBatchCollisionsReceivePredictableSuffixes() {
        val names = ExportFileNames(listOf("BOOK.EPUB", "Book (2).epub", "CAFÉ.epub"))
        assertEquals("Book (3).epub", names.next("Book.epub", "epub"))
        assertEquals("Book (4).epub", names.next("Book.epub", "epub"))
        assertEquals("cafe\u0301 (2).epub", names.next("cafe\u0301.epub", "epub"))
    }

    @Test
    fun namesAssignedByProvidersAreReservedForTheRestOfTheBatch() {
        val names = ExportFileNames(emptyList())
        assertEquals("Book.epub", names.next("Book.epub", "epub"))
        names.reserve("Other.epub")
        assertEquals("Other (2).epub", names.next("Other.epub", "epub"))
    }

    @Test
    fun collisionSuffixKeepsTheExtensionAndTheUtf8Limit() {
        val existing = "🙂".repeat(62) + ".epub"
        val result = checkNotNull(ExportFileNames(listOf(existing)).next(existing, "epub"))
        assertTrue(result.endsWith(" (2).epub"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals("🙂".repeat(61) + " (2).epub", result)
    }

    @Test
    fun individualAndDirectoryExportUseTheCurrentFilenameWithASafeFallback() {
        assertEquals("Renamed.EPUB", suggestedExportName("Renamed.EPUB", "Embedded", "epub", "Untitled"))
        assertEquals("Embedded.epub", suggestedExportName("bad:name.epub", "Embedded", "epub", "Untitled"))
        assertEquals("Untitled.epub", suggestedExportName(null, "", "epub", "Untitled"))
    }
}
