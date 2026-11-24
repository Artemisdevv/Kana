package com.artemis.ebookreader.data.library

import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.artemis.ebookreader.data.storage.Sha256Pattern
import com.artemis.ebookreader.data.storage.sourceFilename
import java.io.File
import java.io.IOException

internal const val LibraryDatabaseName = "ebook-reader.db"

internal enum class StorageCategory { Books, Covers, Dictionary, Cache, ReadingData, Other }

internal data class StorageOverview(
    val sizes: Map<StorageCategory, Long>,
    val importedBooks: Int,
    val booksOnDevice: Int,
    val importedBytes: Long,
    val availableBytes: Long,
    val removableBytes: Long,
    val removableFiles: Int,
) {
    val totalBytes: Long get() = sizes.values.sum()
}

internal enum class IntegrityIssueKind { Missing, Mismatched, UnreferencedBooks, OrphanedCovers, AbandonedImports, PendingDeletion, Unexpected }

internal data class IntegrityIssue(
    val kind: IntegrityIssueKind,
    val name: String,
    val bytes: Long? = null,
)

internal data class StorageReport(val overview: StorageOverview, val issues: List<IntegrityIssue> = emptyList())
internal data class CacheCleanup(val bytes: Long, val removed: Int, val failed: Int)
internal data class StorageCatalog(val books: List<Book>, val assets: List<ImportedFile>)

internal data class StoragePaths(
    val data: File,
    val books: File,
    val covers: File,
    val staging: File,
    val trash: File,
    val dictionary: File,
    val cache: File,
    val codeCache: File,
    val dataStore: File,
    val database: File,
) {
    private val libraryDirectories = listOf(books, covers, staging, trash)
    private val databaseFiles = setOf(database.name, "${database.name}-wal", "${database.name}-shm", "${database.name}-journal")

    fun category(file: File): StorageCategory = when {
        file.parentFile == books && file.isStoredBookName() -> StorageCategory.Books
        file.parentFile == covers && file.isStoredCoverName() -> StorageCategory.Covers
        file.isWithin(dictionary) -> StorageCategory.Dictionary
        file.isWithin(staging) || file.isWithin(cache) || file.isWithin(codeCache) -> StorageCategory.Cache
        file.isWithin(dataStore) || (file.parentFile == database.parentFile && file.name in databaseFiles) ->
            StorageCategory.ReadingData
        else -> StorageCategory.Other
    }

    fun isLibraryPath(file: File): Boolean = libraryDirectories.any { root ->
        file == root || file.isWithin(root)
    }
}

internal enum class StorageNodeType { File, Directory, Other }

internal data class StorageEntry(
    val file: File,
    val type: StorageNodeType,
    val bytes: Long = 0,
    val device: Long = 0,
    val inode: Long = 0,
    val links: Long = 1,
)

internal fun inspectStorageEntry(file: File): StorageEntry? = try {
    val stat = Os.lstat(file.path)
    val kind = when {
        OsConstants.S_ISREG(stat.st_mode) -> StorageNodeType.File
        OsConstants.S_ISDIR(stat.st_mode) -> StorageNodeType.Directory
        else -> StorageNodeType.Other
    }
    StorageEntry(file, kind, stat.st_size.coerceAtLeast(0), stat.st_dev, stat.st_ino, stat.st_nlink)
} catch (error: ErrnoException) {
    if (error.errno == OsConstants.ENOENT) null else throw IOException("Could not inspect app-managed storage", error)
}

private fun File.isWithin(directory: File): Boolean = path.startsWith(directory.path + File.separator)

private fun File.isStoredBookName() = Sha256Pattern.matches(nameWithoutExtension) && BookFormat.fromStorage(extension) != null
private fun File.isStoredCoverName() = Sha256Pattern.matches(nameWithoutExtension) && extension == "jpg"
private val importScratchName = Regex("(?:book|cover)-[0-9]+\\.part")

internal fun isRemovableScratch(entry: StorageEntry, staging: File, active: Set<File>): Boolean =
    entry.type == StorageNodeType.File && entry.links == 1L && entry.file.parentFile == staging &&
        importScratchName.matches(entry.file.name) && entry.file !in active

internal fun scanLibraryStorage(
    paths: StoragePaths,
    catalog: StorageCatalog,
    active: Set<File>,
    checkIntegrity: Boolean,
    checkCancellation: () -> Unit,
): StorageReport {
    val sizes = StorageCategory.entries.associateWith { 0L }.toMutableMap()
    val owned = linkedMapOf<File, StorageEntry>()
    val issues = mutableListOf<IntegrityIssue>()
    val linkedFiles = mutableSetOf<Pair<Long, Long>>()
    var scratchBytes = 0L
    var scratchFiles = 0
    val pending = ArrayDeque<File>().apply { add(paths.data) }
    while (pending.isNotEmpty()) {
        checkCancellation()
        val file = pending.removeLast()
        val entry = inspectStorageEntry(file) ?: continue
        if (paths.isLibraryPath(file)) owned[file] = entry
        when (entry.type) {
            StorageNodeType.Directory -> pending.addAll(listStorageChildren(file).sortedByDescending(File::getName))
            StorageNodeType.File -> {
                val category = paths.category(file)
                if (entry.links == 1L || linkedFiles.add(entry.device to entry.inode)) {
                    sizes[category] = sizes.getValue(category) + entry.bytes
                }
                if (isRemovableScratch(entry, paths.staging, active)) {
                    scratchFiles++
                    scratchBytes += entry.bytes
                }
            }
            StorageNodeType.Other -> Unit
        }
    }

    val assets = catalog.assets.associateBy(ImportedFile::sha256)
    val expected = mutableSetOf<File>()
    val bookDigests = catalog.books.mapTo(mutableSetOf(), Book::assetSha256)
    var libraryBooks = 0
    var libraryBytes = 0L
    for (book in catalog.books) {
        val asset = assets[book.assetSha256]
        val format = asset?.let { BookFormat.fromStorage(it.format) }
        val label = sourceFilename(book.sourceName) ?: book.title.takeUnless(String::isBlank) ?: book.bookId
        if (asset == null || format == null || !Sha256Pattern.matches(book.assetSha256)) {
            if (checkIntegrity) issues += IntegrityIssue(IntegrityIssueKind.Unexpected, label)
            continue
        }
        val file = File(paths.books, "${book.assetSha256}.${format.extension}")
        expected += file
        val entry = owned[file]
        if (entry?.type != StorageNodeType.File) {
            if (checkIntegrity) issues += IntegrityIssue(IntegrityIssueKind.Missing, label)
        } else {
            libraryBooks++
            libraryBytes += entry.bytes
            if (checkIntegrity && (entry.bytes != asset.byteLength || asset.state != "PRESENT")) {
                issues += IntegrityIssue(IntegrityIssueKind.Mismatched, label, entry.bytes)
            }
        }
    }
    if (checkIntegrity) {
        for ((file, entry) in owned) {
            val kind = when {
                entry.type == StorageNodeType.Other -> IntegrityIssueKind.Unexpected
                entry.type != StorageNodeType.File -> null
                file.parentFile == paths.books -> when {
                    file in expected -> null
                    file.isStoredBookName() -> IntegrityIssueKind.UnreferencedBooks
                    else -> IntegrityIssueKind.Unexpected
                }
                file.parentFile == paths.covers -> when {
                    !file.isStoredCoverName() -> IntegrityIssueKind.Unexpected
                    file.nameWithoutExtension !in bookDigests -> IntegrityIssueKind.OrphanedCovers
                    else -> null
                }
                file.parentFile == paths.staging && file !in active -> {
                    if (importScratchName.matches(file.name)) IntegrityIssueKind.AbandonedImports else IntegrityIssueKind.Unexpected
                }
                file.parentFile == paths.trash -> IntegrityIssueKind.PendingDeletion
                else -> null
            }
            if (kind != null) issues += IntegrityIssue(kind, file.name, entry.bytes.takeIf { entry.type == StorageNodeType.File })
        }
        for (asset in catalog.assets) {
            if (asset.sha256 !in bookDigests && owned.keys.none {
                    it.parentFile == paths.books && it.nameWithoutExtension == asset.sha256 && it.isStoredBookName()
                }) {
                issues += IntegrityIssue(IntegrityIssueKind.Unexpected, asset.sha256)
            }
        }
    }
    // Avoid probing a linked or missing books directory. Its parent is the same app-private volume
    // before the first import creates the directory.
    val volume = if (owned[paths.books]?.type == StorageNodeType.Directory) paths.books else paths.data
    return StorageReport(
        StorageOverview(sizes, catalog.books.size, libraryBooks, libraryBytes, StatFs(volume.path).availableBytes, scratchBytes, scratchFiles),
        issues,
    )
}

internal fun listStorageChildren(directory: File): List<File> = directory.listFiles()?.toList()
    ?: if (inspectStorageEntry(directory) == null) emptyList() else throw IOException("Could not list app-managed storage")

internal fun clearImportScratch(staging: File, active: Set<File>): CacheCleanup {
    if (inspectStorageEntry(staging)?.type != StorageNodeType.Directory) return CacheCleanup(0, 0, 0)
    var bytes = 0L
    var removed = 0
    var failed = 0
    for (file in listStorageChildren(staging)) {
        try {
            val entry = inspectStorageEntry(file) ?: continue
            if (!isRemovableScratch(entry, staging, active)) continue
            // File.delete removes this directory entry without traversing its target.
            if (file.delete()) {
                bytes += entry.bytes
                removed++
            } else if (inspectStorageEntry(file) != null) {
                failed++
            }
        } catch (_: IOException) {
            failed++
        }
    }
    return CacheCleanup(bytes, removed, failed)
}
