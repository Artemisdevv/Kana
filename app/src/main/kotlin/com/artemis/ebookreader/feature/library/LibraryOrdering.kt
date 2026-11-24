package com.artemis.ebookreader.feature.library

import android.icu.text.CollationKey
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import com.artemis.ebookreader.data.library.ShelfBook
import com.artemis.ebookreader.data.library.displayName
import com.artemis.ebookreader.data.preferences.LibraryOrganization
import com.artemis.ebookreader.data.preferences.LibrarySortBy
import com.artemis.ebookreader.data.preferences.LibrarySortOrder

internal data class LibraryItem(val shelf: ShelfBook, val displayName: String?, val author: String)

internal fun orderLibrary(books: List<ShelfBook>, preferences: LibraryOrganization): List<LibraryItem> {
    val collator = Collator.getInstance().apply {
        strength = Collator.SECONDARY
        if (this is RuleBasedCollator) numericCollation = true
    }
    val sorted = books.map { shelf ->
        val book = shelf.book
        val name = book.displayName(preferences.displayName)
        val author = book.authorLine
        SortableBook(
            LibraryItem(shelf, name, author),
            name?.let(collator::getCollationKey),
            author.takeIf { preferences.sortBy == LibrarySortBy.Author && it.isNotBlank() }
                ?.let(collator::getCollationKey),
        )
    }
    val descending = preferences.sortOrder == LibrarySortOrder.Descending
    return sorted.sortedWith { a, b ->
        val left = a.item.shelf
        val right = b.item.shelf
        val primary = when (preferences.sortBy) {
            LibrarySortBy.Name -> compareKnown(a.name, b.name, descending)
            LibrarySortBy.Author -> compareKnown(a.author, b.author, descending)
            LibrarySortBy.DateImported -> compareKnown(left.book.importedAt.known(), right.book.importedAt.known(), descending)
            LibrarySortBy.LastRead -> compareKnown(left.lastReadAt, right.lastReadAt, descending)
            LibrarySortBy.FileSize -> compareKnown(left.fileSize?.known(), right.fileSize?.known(), descending)
        }
        if (primary != 0) primary else {
            val byName = compareKnown(a.name, b.name, descending = false)
            if (byName != 0) byName else left.book.bookId.compareTo(right.book.bookId)
        }
    }.map(SortableBook::item)
}

private data class SortableBook(val item: LibraryItem, val name: CollationKey?, val author: CollationKey?)

private fun Long.known(): Long? = takeIf { it > 0 }

private fun <T : Comparable<T>> compareKnown(left: T?, right: T?, descending: Boolean): Int = when {
    left == null -> if (right == null) 0 else 1
    right == null -> -1
    descending -> right.compareTo(left)
    else -> left.compareTo(right)
}
