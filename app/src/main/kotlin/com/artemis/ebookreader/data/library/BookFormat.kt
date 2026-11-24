package com.artemis.ebookreader.data.library

internal enum class BookFormat(val extension: String, val mimeType: String) {
    Epub("epub", "application/epub+zip");

    companion object {
        fun fromStorage(value: String) = entries.firstOrNull { it.extension.equals(value, ignoreCase = true) }
    }
}
