package com.artemis.ebookreader.data.library

import com.artemis.ebookreader.data.preferences.BookDisplayName
import com.artemis.ebookreader.data.storage.bookFilename
import com.artemis.ebookreader.data.storage.bookTitle

internal fun Book.displayName(mode: BookDisplayName): String? = when (mode) {
    BookDisplayName.Filename -> bookFilename(sourceName) ?: bookTitle(title, null)
    BookDisplayName.BookTitle -> bookTitle(title, sourceName)
}

internal fun Book.matchesSearch(term: String): Boolean =
    term.isEmpty() || sourceName?.contains(term, ignoreCase = true) == true ||
        title.contains(term, ignoreCase = true) || authorLine.contains(term, ignoreCase = true)
