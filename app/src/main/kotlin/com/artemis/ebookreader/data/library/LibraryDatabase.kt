package com.artemis.ebookreader.data.library

import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "imported_files")
internal data class ImportedFile(
    @PrimaryKey val sha256: String,
    val format: String,
    val byteLength: Long,
    val state: String,
)

@Entity(
    tableName = "books",
    foreignKeys = [ForeignKey(ImportedFile::class, ["sha256"], ["assetSha256"])],
    indices = [Index(value = ["assetSha256"], unique = true)],
)
internal data class Book(
    @PrimaryKey val bookId: String,
    val assetSha256: String,
    val title: String,
    val authorsJson: String,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    val sourceName: String?,
    val importedAt: Long,
) {
    @delegate:Ignore
    val authorLine: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        JSONArray(authorsJson).let { authors ->
            List(authors.length()) { authors.getString(it) }.joinToString(", ")
        }
    }
}

@Entity(
    tableName = "reading_state",
    foreignKeys = [ForeignKey(Book::class, ["bookId"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["lastReadAt", "bookId"])],
)
internal data class ReadingState(
    @PrimaryKey val bookId: String,
    val lastReadAt: Long?,
    val positionUpdatedAt: Long? = null,
    val locator: String? = null,
    val section: String? = null,
    val progression: Double? = null,
    val historyHiddenAt: Long? = null,
)

internal data class ReadingReset(val previous: ReadingState, val token: Long)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(Book::class, ["bookId"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "locationKey"], unique = true)],
)
internal data class Bookmark(
    @PrimaryKey val bookmarkId: String,
    val bookId: String,
    val locationKey: String,
    val locator: String,
    val chapter: String?,
    val excerpt: String?,
    val createdAt: Long,
    val spineIndex: Int,
    val resourceProgression: Double?,
    val totalProgression: Double?,
)

internal data class BookmarkSaveResult(val bookmark: Bookmark, val inserted: Boolean)

internal data class ShelfBook(
    @Embedded val book: Book,
    val available: Boolean,
    val lastReadAt: Long?,
    val section: String?,
    val progression: Double?,
    val fileSize: Long?,
    val format: String,
)

internal data class DetailsRecord(
    @Embedded val shelf: ShelfBook,
    val checkpoint: String?,
    val bookmarkCount: Int,
)

private const val ShelfColumns = """
    SELECT b.*, (f.state = 'PRESENT') AS available, s.lastReadAt, s.section, s.progression,
           f.byteLength AS fileSize, f.format
    FROM books b JOIN imported_files f ON b.assetSha256 = f.sha256
"""
private const val HistoryOrder = " ORDER BY s.lastReadAt DESC, b.bookId ASC"

@Dao
internal abstract class LibraryDao {
    @Query(ShelfColumns + " LEFT JOIN reading_state s ON b.bookId = s.bookId")
    abstract fun library(): Flow<List<ShelfBook>>

    @Query(ShelfColumns + " JOIN reading_state s ON b.bookId = s.bookId WHERE s.lastReadAt IS NOT NULL AND s.historyHiddenAt IS NULL" + HistoryOrder)
    abstract fun history(): Flow<List<ShelfBook>>

    @Query(ShelfColumns + " JOIN reading_state s ON b.bookId = s.bookId WHERE s.lastReadAt IS NOT NULL" + HistoryOrder + " LIMIT 5")
    abstract fun recent(): Flow<List<ShelfBook>>

    @Query("""
        SELECT b.*, (f.state = 'PRESENT') AS available, s.lastReadAt, s.section, s.progression,
            f.byteLength AS fileSize, f.format, s.locator AS checkpoint,
            (SELECT COUNT(*) FROM bookmarks m WHERE m.bookId = b.bookId) AS bookmarkCount
        FROM books b JOIN imported_files f ON f.sha256 = b.assetSha256
        LEFT JOIN reading_state s ON b.bookId = s.bookId
        WHERE b.bookId = :bookId
    """)
    abstract fun details(bookId: String): Flow<DetailsRecord?>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    abstract suspend fun book(bookId: String): Book?

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    abstract fun observeBook(bookId: String): Flow<Book?>

    @Query("SELECT * FROM books WHERE assetSha256 = :sha256")
    abstract suspend fun bookByDigest(sha256: String): Book?

    @Query("SELECT * FROM imported_files WHERE sha256 = :sha256")
    abstract suspend fun file(sha256: String): ImportedFile?

    @Query("SELECT * FROM imported_files")
    abstract suspend fun files(): List<ImportedFile>

    @Query("SELECT * FROM books")
    abstract suspend fun catalogBooks(): List<Book>

    @Transaction
    open suspend fun storageCatalog() = StorageCatalog(catalogBooks(), files())

    @Query("""
        SELECT b.sourceName FROM books b JOIN imported_files f ON b.assetSha256 = f.sha256
        WHERE b.bookId != :bookId AND f.format = :format AND b.sourceName IS NOT NULL
    """)
    abstract suspend fun otherFilenames(bookId: String, format: String): List<String>

    @Query("UPDATE books SET sourceName = :filename WHERE bookId = :bookId")
    abstract suspend fun renameFile(bookId: String, filename: String): Int

    @Query("DELETE FROM books WHERE bookId = :bookId")
    abstract suspend fun deleteBookRow(bookId: String): Int

    @Query("DELETE FROM imported_files WHERE sha256 = :digest")
    abstract suspend fun deleteFileRow(digest: String): Int

    @Transaction
    open suspend fun deleteCatalogEntry(bookId: String, digest: String) {
        check(deleteBookRow(bookId) == 1)
        check(deleteFileRow(digest) == 1)
    }

    @Query("SELECT * FROM reading_state WHERE bookId = :bookId")
    abstract suspend fun readingState(bookId: String): ReadingState?

    @Query("""
        SELECT * FROM bookmarks WHERE bookId = :bookId
        ORDER BY spineIndex, resourceProgression, createdAt, bookmarkId
    """)
    abstract fun bookmarks(bookId: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND locationKey = :locationKey")
    abstract suspend fun bookmarkAt(bookId: String, locationKey: String): Bookmark?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBookmark(bookmark: Bookmark): Long

    @Transaction
    open suspend fun saveBookmark(bookmark: Bookmark): BookmarkSaveResult {
        if (insertBookmark(bookmark) != -1L) return BookmarkSaveResult(bookmark, inserted = true)
        val existing = checkNotNull(bookmarkAt(bookmark.bookId, bookmark.locationKey))
        return BookmarkSaveResult(existing, inserted = false)
    }

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND bookmarkId = :bookmarkId")
    abstract suspend fun deleteBookmark(bookId: String, bookmarkId: String): Int

    @Query("""
        SELECT * FROM highlights WHERE bookId = :bookId
        ORDER BY spineIndex, resourceProgression IS NULL, resourceProgression, createdAt, highlightId
    """)
    abstract fun highlights(bookId: String): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND rangeKey = :key")
    abstract suspend fun highlightAt(bookId: String, key: String): Highlight?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertHighlight(highlight: Highlight): Long

    @Query("""
        UPDATE highlights SET colorKey = :colorKey, modifiedAt = :now
        WHERE bookId = :bookId AND highlightId = :id AND colorKey != :colorKey
    """)
    abstract suspend fun setHighlightColor(bookId: String, id: String, colorKey: String, now: Long): Int

    @Query("""
        UPDATE highlights SET note = :note, modifiedAt = :now
        WHERE bookId = :bookId AND highlightId = :id AND note IS NOT :note
    """)
    abstract suspend fun setHighlightNote(bookId: String, id: String, note: String?, now: Long): Int

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND highlightId = :id")
    abstract suspend fun highlight(bookId: String, id: String): Highlight?

    @Query("DELETE FROM highlights WHERE bookId = :bookId AND highlightId = :id")
    abstract suspend fun deleteHighlight(bookId: String, id: String): Int

    @Transaction
    open suspend fun saveHighlight(candidate: Highlight): Boolean {
        if (insertHighlight(candidate) != -1L) return true
        val existing = checkNotNull(highlightAt(candidate.bookId, candidate.rangeKey))
        if (candidate.note != null) {
            setHighlightNote(existing.bookId, existing.highlightId, candidate.note, candidate.modifiedAt)
            return false
        }
        return setHighlightColor(existing.bookId, existing.highlightId, candidate.colorKey, candidate.modifiedAt) != 0
    }

    @Transaction
    open suspend fun removeHighlight(bookId: String, id: String): Highlight? {
        val previous = highlight(bookId, id) ?: return null
        return previous.takeIf { deleteHighlight(bookId, id) == 1 }
    }

    @Insert
    abstract suspend fun insertFile(file: ImportedFile)

    @Insert
    abstract suspend fun insertBook(book: Book)

    @Query("UPDATE imported_files SET state = :state WHERE sha256 = :sha256")
    abstract suspend fun setAvailability(sha256: String, state: String)

    @Transaction
    open suspend fun addToLibrary(file: ImportedFile, book: Book) {
        insertFile(file)
        insertBook(book)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertReadingState(state: ReadingState)

    @Query("""
        UPDATE reading_state SET lastReadAt = MAX(COALESCE(lastReadAt, :now), :now),
        historyHiddenAt = CASE WHEN :opened THEN NULL ELSE historyHiddenAt END
        WHERE bookId = :bookId AND (:opened OR lastReadAt IS NOT NULL)
    """)
    abstract suspend fun touchReadingState(bookId: String, now: Long, opened: Boolean)

    @Update
    abstract suspend fun updateReadingState(state: ReadingState)

    @Transaction
    open suspend fun resetReading(bookId: String, token: Long): ReadingReset? {
        // Keep a marker for this reset so an older Undo cannot replace a new reading session.
        val cleared = ReadingState(bookId, lastReadAt = null, progression = 0.0, historyHiddenAt = token)
        val previous = readingState(bookId)
        if (previous == null) {
            insertReadingState(cleared)
            return null
        }
        updateReadingState(cleared)
        return previous.takeIf {
            it.lastReadAt != null || it.locator != null || it.positionUpdatedAt != null || it.section != null ||
                (it.progression != null && it.progression != 0.0)
        }?.let { ReadingReset(it, token) }
    }

    @Transaction
    open suspend fun undoReset(reset: ReadingReset) {
        val current = readingState(reset.previous.bookId) ?: return
        if (current.lastReadAt == null && current.historyHiddenAt == reset.token) {
            updateReadingState(reset.previous)
        }
    }

    @Query("""
        UPDATE reading_state SET locator = :locator, positionUpdatedAt = :now,
        section = :section, progression = :progression
        WHERE bookId = :bookId AND lastReadAt IS NOT NULL
    """)
    abstract suspend fun updateCheckpoint(
        bookId: String,
        locator: String,
        section: String?,
        progression: Double?,
        now: Long,
    )

    @Transaction
    open suspend fun recordReading(
        bookId: String,
        now: Long,
        locator: String?,
        section: String?,
        progression: Double?,
    ) {
        insertReadingState(ReadingState(bookId, now))
        touchReadingState(bookId, now, opened = locator == null)
        if (locator != null) {
            updateCheckpoint(bookId, locator, section, progression, now)
        }
    }
}

@Database(
    entities = [Book::class, ImportedFile::class, ReadingState::class, Bookmark::class, Highlight::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
    ],
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
