package com.artemis.ebookreader.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val WORDNET_LICENSE_ASSET = "dictionary/LICENSE-WordNet-3.0.txt"

internal class OfflineDictionary(private val context: Context) {
    // Initialized on the IO dispatcher by lookup. The immutable database is shared by both readers.
    private val database by lazy {
        val directory = File(context.noBackupFilesDir, "dictionary")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Dictionary storage unavailable")
        val file = File(directory, "wordnet-3.0-v1.sqlite")
        if (!file.isFile) {
            val stagingFile = File(directory, "${file.name}.part")
            try {
                context.assets.open("dictionary/wordnet-3.0.sqlite").use { asset ->
                    stagingFile.outputStream().buffered().use { asset.copyTo(it, 32 * 1024) }
                }
                if (!stagingFile.renameTo(file)) throw IOException("Could not install dictionary")
            } finally {
                stagingFile.delete()
            }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    suspend fun lookup(query: DictionaryQuery): DictionaryResult = withContext(Dispatchers.IO) {
        try {
            val entries = lookupWordNet(query.key, { exceptions(query.key) }, ::entry)
            if (entries.isEmpty()) DictionaryResult.Missing else DictionaryResult.Found(entries)
        } catch (_: IOException) {
            DictionaryResult.Unavailable
        } catch (_: SQLiteException) {
            DictionaryResult.Unavailable
        }
    }

    private fun exceptions(word: String): List<WordNetForm> = database.rawQuery(
        "SELECT lemmas FROM exception WHERE word = ?", arrayOf(word),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use emptyList()
        cursor.getString(0).lineSequence().mapNotNull { line ->
            val part = line.substringBefore('\t').toIntOrNull()?.let(WordNetPart.entries::getOrNull)
            val lemma = line.substringAfter('\t', "")
            if (part == null || lemma.isEmpty()) null else WordNetForm(lemma, part)
        }.toList()
    }

    private fun entry(word: String, parts: Set<WordNetPart>?): DictionaryEntry? {
        val ids = database.rawQuery("SELECT senses FROM term WHERE word = ?", arrayOf(word)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytes = cursor.getBlob(0)
            if (bytes.size !in 4..2048 || bytes.size % 4 != 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / 4) { buffer.int }
        }
        val placeholders = ids.joinToString(",") { "?" }
        val senses = database.rawQuery(
            "SELECT id, pos, gloss FROM sense WHERE id IN ($placeholders)",
            ids.map(Int::toString).toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val part = WordNetPart.entries[cursor.getInt(1)]
                    if (parts != null && part !in parts) continue
                    val gloss = cursor.getString(2).lines()
                    put(cursor.getInt(0), part to DictionarySense(gloss.first(), gloss.drop(1)))
                }
            }
        }
        val meanings = ids.mapNotNull(senses::get).groupBy({ it.first }, { it.second })
            .map { (part, definitions) -> DictionaryMeaning(part.label, definitions) }
        return meanings.takeIf { it.isNotEmpty() }?.let { DictionaryEntry(word, it) }
    }
}
