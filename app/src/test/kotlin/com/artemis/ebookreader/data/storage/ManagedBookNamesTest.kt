package com.artemis.ebookreader.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedBookNamesTest {
    @Test
    fun normalizesTheNameAndKeepsOnlyOneFormatExtension() {
        assertEquals("Book One.epub", validatedBookFilename("  Book One.EPUB.epub  ", "epub"))
        assertEquals("Part 2.1.epub", validatedBookFilename("Part 2.1", "epub"))
        assertEquals("Book", filenameStem("folder/Book.EPUB", "epub"))
        assertEquals("Book.EPUB", currentFilename("folder/Book.EPUB", "epub"))
        assertEquals("Book.epub", currentFilename("Book", "epub"))
    }

    @Test
    fun rejectsTraversalControlCharactersReservedNamesAndExcessiveByteLength() {
        listOf("", "   ", ".", "..", "../Book", "folder\\Book", "a:b", "Book\u0000",
            "Book\n", "NUL", "CON.txt", "Trailing.", ".epub", "é".repeat(126)).forEach { name ->
            assertNull(name, validatedBookFilename(name, "epub"))
        }
        assertEquals("é".repeat(125) + ".epub", validatedBookFilename("é".repeat(125), "epub"))
    }

    @Test
    fun matchesCaseAndUnicodeVariantsForCollisionDetection() {
        assertEquals(filenameCollisionKey("CAFÉ.EPUB"), filenameCollisionKey("cafe\u0301.epub"))
    }
}
