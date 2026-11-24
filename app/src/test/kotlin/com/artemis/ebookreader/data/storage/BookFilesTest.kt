package com.artemis.ebookreader.data.storage

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookFilesTest {
    @Test
    fun copiesBytesAndComputesStableSha256() {
        val source = "abc".toByteArray()
        val destination = ByteArrayOutputStream()
        val first = copyAndHash(source.inputStream(), destination)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first.sha256)
        assertEquals(3L, first.size)
        assertArrayEquals(source, destination.toByteArray())
        assertEquals(first, copyAndHash(source.inputStream()))
        assertNotEquals(first.sha256, copyAndHash("abd".byteInputStream()).sha256)
    }

    @Test
    fun refusesBytesBeyondLimit() {
        assertThrows(BookTooLarge::class.java) { copyAndHash("abc".byteInputStream(), limit = 2) }
    }

    @Test
    fun usesPublicationTitleThenCleanFileNameThenFallback() {
        assertEquals("From metadata", bookTitle(" From metadata ", "file.epub", "Untitled book"))
        assertEquals("Filename", bookTitle(" ", "folder/Filename.EPUB", "Untitled book"))
        assertEquals("Untitled book", bookTitle(null, ".epub", "Untitled book"))
        assertEquals("Untitled book", bookTitle(null, null, "Untitled book"))
    }
}
