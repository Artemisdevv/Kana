package com.artemis.ebookreader.data.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.artemis.ebookreader.data.storage.ExportFileNames
import java.io.IOException

internal class BookExportDirectory(resolver: ContentResolver, tree: Uri) {
    private val resolver = resolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    private val ids = mutableSetOf<String>()
    private val names = run {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val existing = resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                buildList<String> {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0)
                        val displayName = cursor.getString(1)
                        if (documentId != null) ids.add(documentId)
                        if (displayName != null) add(displayName)
                    }
                }
            } ?: throw IOException("Could not list the export directory")
        ExportFileNames(existing)
    }

    data class Destination(val uri: Uri, val name: String)

    fun create(preferred: String, format: BookFormat): Destination {
        val candidate = names.next(preferred, format.extension) ?: throw IOException("Could not find an unused filename")
        val uri = DocumentsContract.createDocument(resolver, parent, format.mimeType, candidate)
            ?: throw IOException("Could not create the export document")
        if (uri.authority != parent.authority) throw IOException("Invalid export document provider")
        // A provider must return a new document. Never write to one already listed in the directory.
        val id = DocumentsContract.getDocumentId(uri)
        if (id == treeId || !ids.add(id)) throw IOException("The provider returned an existing document")
        val name = runCatching {
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: candidate
        names.reserve(name)
        return Destination(uri, name)
    }
}
