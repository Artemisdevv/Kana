package com.artemis.ebookreader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.artemis.ebookreader.data.storage.BookTooLarge
import com.artemis.ebookreader.data.storage.Sha256Pattern
import com.artemis.ebookreader.data.storage.copyAndHash
import com.artemis.ebookreader.data.storage.currentFilename
import com.artemis.ebookreader.data.storage.filenameCollisionKey
import com.artemis.ebookreader.data.storage.suggestedExportName
import com.artemis.ebookreader.data.storage.validatedBookFilename
import com.artemis.ebookreader.publication.BookFailure
import com.artemis.ebookreader.publication.BookProblem
import com.artemis.ebookreader.publication.EpubEngine
import com.artemis.ebookreader.publication.SavedLocation
import com.artemis.ebookreader.publication.bookmarkKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.use

internal enum class ImportResult { Added, Duplicate, Relinked }
internal enum class RenameResult { Renamed, InvalidName, Conflict, Missing }
internal data class DeleteResult(val digest: String, val cleanupPending: Boolean)
internal class DeleteFailure(val recoveryPending: Boolean, cause: Exception) : IOException(cause)
internal class ExportFailure(val cleanupFailed: Boolean, cause: Exception) : IOException(cause)
internal class ExportCancelled(val cleanupFailed: Boolean, reason: CancellationException) : CancellationException(reason.message) {
    init { initCause(reason) }
}
internal data class BookDetails(val shelf: ShelfBook, val chapter: String?, val bookmarkCount: Int)

internal class OpenBook(
    val book: Book,
    val publication: Publication,
    val readingState: ReadingState?,
    val readingGeneration: Long?,
) : AutoCloseable {
    override fun close() = publication.close()
}

internal data class ResolvedBookmark(val record: Bookmark, val location: SavedLocation)

internal class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    root: File = context.filesDir,
) {
    private val engine = EpubEngine(context)
    private val books = File(root, "books")
    private val covers = File(root, "book-covers")
    private val staging = File(root, "book-staging")
    private val trash = File(root, "book-trash")
    private val fileGate = Mutex()
    private val activeImports = mutableSetOf<File>()
    private val readingGate = Mutex()
    private val readingGenerations = mutableMapOf<String, Long>()
    private var recovered = false
    private var checkTrash = true

    val library = dao.library()
    val recent = dao.recent()
    fun observeBook(bookId: String) = dao.observeBook(bookId)

    suspend fun resetReading(bookId: String): ReadingReset? = readingGate.withLock {
        withContext(NonCancellable) {
            if (dao.book(bookId) == null) return@withContext null
            val token = System.nanoTime()
            dao.resetReading(bookId, token).also { readingGenerations[bookId] = token }
        }
    }

    suspend fun undoReset(reset: ReadingReset) = readingGate.withLock {
        dao.undoReset(reset)
    }
    val history = dao.history()

    fun details(bookId: String) = dao.details(bookId).map { record ->
        record?.let {
            val locator = SavedLocation.decode(it.checkpoint, it.shelf.book.assetSha256)?.locator
            val resourceName = locator?.href?.removeFragment()?.toString()?.substringAfterLast('/')
            val chapter = locator?.let { position ->
                it.shelf.section?.takeUnless { name -> name.isBlank() || name == resourceName }
                    ?: position.title?.takeUnless(String::isBlank)
            }
            BookDetails(it.shelf, chapter, it.bookmarkCount)
        }
    }.flowOn(Dispatchers.Default)

    fun bookmarks(book: Book) = dao.bookmarks(book.bookId).map { rows ->
        rows.mapNotNull { row ->
            SavedLocation.decode(row.locator, book.assetSha256)?.let { ResolvedBookmark(row, it) }
        }
    }

    suspend fun saveBookmark(
        book: Book,
        location: SavedLocation,
        chapter: String?,
        spineIndex: Int,
        now: Long,
    ): Bookmark = saveBookmarkWithResult(book, location, chapter, spineIndex, now).bookmark

    suspend fun saveBookmarkWithResult(
        book: Book,
        location: SavedLocation,
        chapter: String?,
        spineIndex: Int,
        now: Long,
    ): BookmarkSaveResult = dao.saveBookmark(
        Bookmark(
            bookmarkId = UUID.randomUUID().toString(),
            bookId = book.bookId,
            locationKey = location.bookmarkKey(),
            locator = location.encode(book.assetSha256),
            chapter = chapter?.takeIf(String::isNotBlank),
            excerpt = location.reflowAnchor?.text?.highlight?.replace(Regex("\\s+"), " ")?.trim()
                ?.take(140)?.takeIf(String::isNotEmpty),
            createdAt = now,
            spineIndex = spineIndex,
            resourceProgression = location.locator.locations.progression,
            totalProgression = location.locator.locations.totalProgression,
        ),
    )

    suspend fun removeBookmark(bookmark: Bookmark): Boolean =
        dao.deleteBookmark(bookmark.bookId, bookmark.bookmarkId) != 0

    suspend fun restoreBookmark(bookmark: Bookmark): Bookmark = restoreBookmarkWithResult(bookmark).bookmark

    suspend fun restoreBookmarkWithResult(bookmark: Bookmark): BookmarkSaveResult = dao.saveBookmark(bookmark)

    fun highlights(bookId: String) = dao.highlights(bookId)
        .map { rows -> rows.mapNotNull(Highlight::resolve) }
        .flowOn(Dispatchers.Default)

    /** Returns true when the transaction inserts a highlight or changes its color. */
    suspend fun saveHighlight(
        bookId: String, selection: Locator, chapter: String?, spineIndex: Int, color: HighlightColor, note: String? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        val locator = selection.highlightAnchor()
        val text = requireNotNull(locator.text.highlight).also {
            require(it.isNotBlank() && it.length <= MaxHighlightLength)
        }
        val now = System.currentTimeMillis()
        dao.saveHighlight(Highlight(
            UUID.randomUUID().toString(), bookId, locator.highlightRangeKey(), locator.toJSON().toString(),
            text, chapter?.takeUnless(String::isBlank), color.key, note?.let(::validatedNote), now, now,
            spineIndex, locator.locations.progression?.takeIf { it in 0.0..1.0 },
        ))
    }

    suspend fun setHighlightColor(bookId: String, id: String, color: HighlightColor): Boolean =
        dao.setHighlightColor(bookId, id, color.key, System.currentTimeMillis()) != 0

    suspend fun setHighlightNote(bookId: String, id: String, note: String?): Boolean =
        dao.setHighlightNote(bookId, id, note?.let(::validatedNote), System.currentTimeMillis()) != 0

    suspend fun removeHighlight(bookId: String, id: String) = dao.removeHighlight(bookId, id)

    suspend fun restoreHighlight(highlight: Highlight): Boolean = dao.insertHighlight(highlight) != -1L

    private fun validatedNote(note: String): String = note.trim().also {
        require(it.isNotEmpty() && it.length <= MaxNoteLength)
    }

    fun cover(sha256: String): File = File(covers, "${checkedDigest(sha256)}.jpg")

    suspend fun storageReport(checkIntegrity: Boolean = false): StorageReport = withContext(Dispatchers.IO) {
        fileGate.withLock {
            val paths = StoragePaths(
                data = context.dataDir,
                books = books,
                covers = covers,
                staging = staging,
                trash = trash,
                dictionary = File(context.noBackupFilesDir, "dictionary"),
                cache = context.cacheDir,
                codeCache = context.codeCacheDir,
                dataStore = File(context.filesDir, "datastore"),
                database = context.getDatabasePath(LibraryDatabaseName),
            )
            scanLibraryStorage(paths, dao.storageCatalog(), activeImports, checkIntegrity) { coroutineContext.ensureActive() }
        }
    }

    suspend fun clearStorageCache(): CacheCleanup = withContext(Dispatchers.IO) {
        fileGate.withLock { clearImportScratch(staging, activeImports) }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        fileGate.withLock {
            if (recovered && !checkTrash) return@withLock
            val assets = dao.files().associateBy(ImportedFile::sha256)
            checkTrash = recoverTrash(assets.keys)
            if (recovered) return@withLock
            // Keep unreferenced imports and covers available for inspection or recovery. Only
            // explicit staged deletions above and confirmed scratch cleanup remove stored files.
            val safeBooksDirectory = inspectStorageEntry(books)?.type == StorageNodeType.Directory
            for (asset in assets.values) {
                val format = BookFormat.fromStorage(asset.format) ?: continue
                val entry = if (safeBooksDirectory && Sha256Pattern.matches(asset.sha256)) {
                    inspectStorageEntry(bookFile(asset.sha256, format))
                } else null
                if (asset.state == Present && (entry?.type != StorageNodeType.File || entry.bytes != asset.byteLength)) {
                    dao.setAvailability(asset.sha256, Missing)
                }
            }
            recovered = true
        }
    }

    suspend fun renameBook(bookId: String, name: String): RenameResult = withContext(Dispatchers.IO) {
        fileGate.withLock {
            val book = dao.book(bookId) ?: return@withLock RenameResult.Missing
            val asset = dao.file(book.assetSha256) ?: return@withLock RenameResult.Missing
            val format = BookFormat.fromStorage(asset.format) ?: return@withLock RenameResult.Missing
            val filename = validatedBookFilename(name, format.extension) ?: return@withLock RenameResult.InvalidName
            if (book.sourceName == filename) return@withLock RenameResult.Renamed
            val key = filenameCollisionKey(filename)
            if (dao.otherFilenames(bookId, asset.format).any {
                    currentFilename(it, format.extension)?.let(::filenameCollisionKey) == key
                }) return@withLock RenameResult.Conflict
            if (dao.renameFile(bookId, filename) == 1) RenameResult.Renamed else RenameResult.Missing
        }
    }

    suspend fun exportBook(bookId: String, destination: Uri, checkCancellation: () -> Unit = {}) {
        try {
            writeBookCopy(bookId, checkCancellation) {
                context.contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("Could not open export destination")
            }
        } catch (failure: Exception) {
            val removed = withContext(NonCancellable + Dispatchers.IO) {
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, destination)
                } catch (_: Exception) {
                    false
                }
            }
            if (failure is CancellationException) throw ExportCancelled(cleanupFailed = !removed, reason = failure)
            throw ExportFailure(cleanupFailed = !removed, cause = failure)
        }
    }

    suspend fun openExportDirectory(tree: Uri) = withContext(Dispatchers.IO) {
        BookExportDirectory(context.contentResolver, tree)
    }

    suspend fun exportBookToDirectory(
        bookId: String,
        directory: BookExportDirectory,
        fallback: String,
        checkCancellation: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        checkCancellation()
        val book = dao.book(bookId) ?: throw BookFailure(BookProblem.Missing)
        val asset = dao.file(book.assetSha256) ?: throw BookFailure(BookProblem.Missing)
        if (asset.state != Present) throw BookFailure(BookProblem.Missing)
        val format = BookFormat.fromStorage(asset.format) ?: throw BookFailure(BookProblem.Unsupported)
        val name = suggestedExportName(book.sourceName, book.title, format.extension, fallback)
        val destination = directory.create(name, format)
        exportBook(bookId, destination.uri, checkCancellation)
        destination.name != name
    }

    // The destination is opened only after the source's availability has been checked.
    suspend fun writeBookCopy(bookId: String, checkCancellation: () -> Unit = {}, destination: () -> OutputStream) = withContext(Dispatchers.IO) {
        recover()
        fileGate.withLock {
            checkCancellation()
            val book = dao.book(bookId) ?: throw BookFailure(BookProblem.Missing)
            val asset = dao.file(book.assetSha256) ?: throw BookFailure(BookProblem.Missing)
            val source = bookFile(asset)
            if (asset.state != Present || !hasPayload(source, asset.byteLength)) throw BookFailure(BookProblem.Missing)
            source.inputStream().use { input ->
                destination().use { output ->
                    val copy = copyAndHash(input, output, limit = asset.byteLength) {
                        coroutineContext.ensureActive()
                        checkCancellation()
                    }
                    if (copy.size != asset.byteLength || copy.sha256 != asset.sha256) {
                        throw BookFailure(BookProblem.Corrupt)
                    }
                }
            }
        }
    }

    suspend fun deleteBook(bookId: String): DeleteResult? = withContext(Dispatchers.IO) {
        recover()
        fileGate.withLock {
            readingGate.withLock deletion@{
                val book = dao.book(bookId) ?: return@deletion null
                val asset = dao.file(book.assetSha256) ?: throw BookFailure(BookProblem.Missing)
                withContext(NonCancellable) {
                    val paths = listOf(bookFile(asset), cover(asset.sha256))
                    val moved = mutableListOf<Pair<File, File>>()
                    try {
                        for (source in paths) {
                            if (!source.exists()) continue
                            if (!source.isFile) throw IOException("Invalid book storage path")
                            if (!trash.isDirectory && !trash.mkdirs()) throw IOException("Could not stage book deletion")
                            val staged = File(trash, source.name)
                            promote(source, staged)
                            moved += source to staged
                        }
                        dao.deleteCatalogEntry(bookId, asset.sha256)
                    } catch (failure: Exception) {
                        var recoveryPending = false
                        for ((source, staged) in moved.asReversed()) {
                            try {
                                promote(staged, source)
                            } catch (restoreFailure: IOException) {
                                failure.addSuppressed(restoreFailure)
                                recoveryPending = true
                            }
                        }
                        if (recoveryPending) checkTrash = true
                        throw DeleteFailure(recoveryPending, failure)
                    }
                    readingGenerations.remove(bookId)
                    val cleanupPending = paths.map { File(trash, it.name) }.anyFileRemains()
                    if (cleanupPending) checkTrash = true
                    DeleteResult(asset.sha256, cleanupPending)
                }
            }
        }
    }

    private fun List<File>.anyFileRemains(): Boolean =
        count { runCatching { it.exists() && !it.delete() }.getOrDefault(true) } != 0

    // A catalog row means deletion never committed. Otherwise the staged files are disposable.
    private fun recoverTrash(liveDigests: Set<String>): Boolean {
        if (inspectStorageEntry(trash)?.type != StorageNodeType.Directory) return false
        var retry = false
        for (staged in listStorageChildren(trash)) {
            if (inspectStorageEntry(staged)?.type != StorageNodeType.File || !Sha256Pattern.matches(staged.nameWithoutExtension)) continue
            val directory = when {
                BookFormat.fromStorage(staged.extension) != null -> books
                staged.extension == "jpg" -> covers
                else -> continue
            }
            val source = File(directory, staged.name)
            if (staged.nameWithoutExtension in liveDigests) {
                // Two copies for a live catalog entry are ambiguous. Preserve both for review.
                if (inspectStorageEntry(directory)?.type == StorageNodeType.Directory && inspectStorageEntry(source) != null) continue
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not restore staged book files")
                if (inspectStorageEntry(directory)?.type != StorageNodeType.Directory) throw IOException("Invalid book storage path")
                promote(staged, source)
            } else if (!staged.delete()) {
                Log.w("LibraryRepository", "Could not clean a staged book file")
                retry = true
            }
        }
        return retry
    }

    suspend fun importBook(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        importBook(name) { resolver.openInputStream(uri) ?: throw BookFailure(BookProblem.Unreadable) }
    }

    suspend fun importBook(sourceName: String?, source: () -> InputStream): ImportResult =
        withContext(Dispatchers.IO) {
            recover()
            val temporary = fileGate.withLock {
                if (!staging.isDirectory && !staging.mkdirs()) throw IOException("Could not create import staging directory")
                if (inspectStorageEntry(staging)?.type != StorageNodeType.Directory) throw IOException("Invalid import staging path")
                File.createTempFile("book-", ".part", staging).also { activeImports.add(it) }
            }
            try {
                val copied = source().use { input ->
                    temporary.outputStream().use { output ->
                        copyAndHash(input, output, checkCancellation = { coroutineContext.ensureActive() })
                            .also { output.fd.sync() }
                    }
                }
                fileGate.withLock {
                    val existing = dao.bookByDigest(copied.sha256)
                    val target = bookFile(copied.sha256, BookFormat.Epub)
                    if (existing != null && dao.file(copied.sha256)?.state == Present &&
                        hasPayload(target, copied.size) &&
                        target.inputStream().use {
                            copyAndHash(it, checkCancellation = { coroutineContext.ensureActive() }).sha256
                        } == copied.sha256
                    ) return@withLock ImportResult.Duplicate

                    engine.open(temporary, sourceName).use { publication ->
                        val metadata = publication.metadata
                        val book = existing ?: Book(
                            bookId = UUID.randomUUID().toString(),
                            assetSha256 = copied.sha256,
                            title = metadata.title.orEmpty(),
                            authorsJson = JSONArray(metadata.authors.map { it.name.trim() }.filter(String::isNotEmpty))
                                .toString(),
                            language = metadata.languages.firstOrNull(),
                            publisher = metadata.publishers.firstOrNull()?.name,
                            identifier = metadata.identifier,
                            sourceName = sourceName,
                            importedAt = System.currentTimeMillis(),
                        )
                        saveCover(publication, copied.sha256)
                        books.mkdirs()
                        promote(temporary, target)
                        if (existing == null) {
                            dao.addToLibrary(ImportedFile(copied.sha256, BookFormat.Epub.extension, copied.size, Present), book)
                            ImportResult.Added
                        } else {
                            dao.setAvailability(copied.sha256, Present)
                            ImportResult.Relinked
                        }
                    }
                }
            } catch (_: BookTooLarge) {
                throw BookFailure(BookProblem.TooLarge)
            } finally {
                withContext(NonCancellable) {
                    fileGate.withLock {
                        activeImports.remove(temporary)
                        temporary.delete()
                    }
                }
            }
        }

    suspend fun openBook(bookId: String): OpenBook {
        var opened: OpenBook? = null
        try {
            return withContext(Dispatchers.IO) {
                recover()
                fileGate.withLock {
                    val book = dao.book(bookId) ?: throw BookFailure(BookProblem.Missing)
                    val asset = dao.file(book.assetSha256) ?: throw BookFailure(BookProblem.Missing)
                    val file = bookFile(asset)
                    val (state, generation) = readingGate.withLock {
                        val saved = dao.readingState(bookId)
                        val token = readingGenerations[bookId] ?: saved?.historyHiddenAt?.also {
                            readingGenerations[bookId] = it
                        }
                        saved?.takeIf { it.lastReadAt != null } to token
                    }
                    if (asset.state == Missing || !hasPayload(file, asset.byteLength)) {
                        dao.setAvailability(asset.sha256, Missing)
                        throw BookFailure(BookProblem.Missing)
                    }
                    val publication = try {
                        engine.open(file, file.name, paginated = true)
                    } catch (failure: BookFailure) {
                        dao.setAvailability(asset.sha256, Missing)
                        throw failure
                    }
                    OpenBook(book, publication, state, generation).also { opened = it }
                }
            }
        } catch (failure: Exception) {
            opened?.close()
            throw failure
        }
    }

    suspend fun recordReading(book: Book, time: Long, location: SavedLocation?) {
        readingGate.withLock {
            writeReading(book, time, location, null)
        }
    }

    suspend fun recordReading(session: OpenBook, time: Long, location: SavedLocation?, chapter: String? = null) {
        readingGate.withLock {
            val book = session.book
            if (readingGenerations[book.bookId] != session.readingGeneration || dao.book(book.bookId) == null) {
                return@withLock
            }
            writeReading(book, time, location, chapter)
        }
    }

    private suspend fun writeReading(book: Book, time: Long, location: SavedLocation?, chapter: String?) {
        dao.recordReading(
            bookId = book.bookId,
            now = time,
            locator = location?.encode(book.assetSha256),
            section = chapter?.takeUnless(String::isBlank) ?: location?.locator?.title?.takeUnless(String::isBlank),
            progression = location?.locator?.locations?.totalProgression?.takeIf { it.isFinite() && it in 0.0..1.0 },
        )
    }

    private fun bookFile(asset: ImportedFile) =
        bookFile(asset.sha256, BookFormat.fromStorage(asset.format) ?: throw BookFailure(BookProblem.Unsupported))

    private fun bookFile(sha256: String, format: BookFormat) = File(books, "${checkedDigest(sha256)}.${format.extension}")

    private fun checkedDigest(sha256: String): String = sha256.also { require(Sha256Pattern.matches(it)) }

    private fun hasPayload(file: File, expectedSize: Long) = file.isFile && file.length() == expectedSize

    private fun promote(from: File, to: File) {
        try {
            Os.rename(from.path, to.path)
        } catch (error: ErrnoException) {
            throw IOException("Could not move book into internal storage.", error)
        }
    }

    private suspend fun saveCover(publication: Publication, sha256: String) {
        try {
            val bitmap = engine.thumbnail(publication) ?: return
            try {
                covers.mkdirs()
                val temporary = File.createTempFile("cover-", ".part", staging)
                try {
                    val written = temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 87, it) }
                    if (written) promote(temporary, cover(sha256))
                } finally {
                    temporary.delete()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("LibraryRepository", "Could not save a cover thumbnail", error)
        }
    }

    private companion object {
        const val Present = "PRESENT"
        const val Missing = "MISSING"
    }
}
