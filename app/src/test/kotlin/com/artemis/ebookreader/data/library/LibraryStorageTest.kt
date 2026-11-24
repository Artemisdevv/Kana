package com.artemis.ebookreader.data.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStorageTest {
    private val root = File("kana-fixture")
    private val paths = StoragePaths(
        root, File(root, "files/books"), File(root, "files/book-covers"), File(root, "files/book-staging"),
        File(root, "files/book-trash"), File(root, "no_backup/dictionary"), File(root, "cache"),
        File(root, "code_cache"), File(root, "files/datastore"), File(root, "databases/$LibraryDatabaseName"),
    )

    @Test
    fun assignsEachOwnedPathToOneCategory() {
        val digest = "a".repeat(64)
        val categories = mapOf(
            File(paths.books, "$digest.epub") to StorageCategory.Books,
            File(paths.covers, "$digest.jpg") to StorageCategory.Covers,
            File(paths.dictionary, "wordnet.sqlite") to StorageCategory.Dictionary,
            File(paths.dictionary, "wordnet.sqlite.part") to StorageCategory.Dictionary,
            File(paths.cache, "any-entry") to StorageCategory.Cache,
            File(paths.codeCache, "startup") to StorageCategory.Cache,
            File(paths.staging, "book-123.part") to StorageCategory.Cache,
            paths.database to StorageCategory.ReadingData,
            File(paths.database.parentFile, "$LibraryDatabaseName-wal") to StorageCategory.ReadingData,
            File(paths.database.parentFile, "$LibraryDatabaseName-shm") to StorageCategory.ReadingData,
            File(paths.database.parentFile, "$LibraryDatabaseName-journal") to StorageCategory.ReadingData,
            File(paths.dataStore, "reader_appearance.preferences_pb") to StorageCategory.ReadingData,
            File(paths.trash, "$digest.epub") to StorageCategory.Other,
            File(paths.books, "unrecognized.epub") to StorageCategory.Other,
            File(root, "cache-unowned/file") to StorageCategory.Other,
        )
        for ((path, category) in categories) assertEquals(path.path, category, paths.category(path))
    }

    @Test
    fun onlyAbandonedRegularScratchFilesInTheDedicatedDirectoryAreRemovable() {
        val eligible = StorageEntry(File(paths.staging, "book-123.part"), StorageNodeType.File, bytes = 20)
        val cover = StorageEntry(File(paths.staging, "cover-456.part"), StorageNodeType.File)
        val active = setOf(eligible.file)
        assertTrue(isRemovableScratch(cover, paths.staging, active))
        assertTrue(isRemovableScratch(eligible, paths.staging, emptySet()))
        assertFalse(isRemovableScratch(eligible, paths.staging, active))
        assertFalse(isRemovableScratch(eligible.copy(links = 2), paths.staging, emptySet()))
        assertFalse(isRemovableScratch(eligible.copy(type = StorageNodeType.Other), paths.staging, emptySet()))
        assertFalse(isRemovableScratch(eligible.copy(file = File(paths.cache, eligible.file.name)), paths.staging, emptySet()))
        assertFalse(isRemovableScratch(eligible.copy(file = File(paths.staging, "anything.part")), paths.staging, emptySet()))
        assertFalse(isRemovableScratch(eligible.copy(file = File(paths.staging, "nested/cover-456.part")), paths.staging, emptySet()))
    }
}
