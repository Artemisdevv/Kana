package com.artemis.ebookreader.data.storage

import java.text.Normalizer
import java.util.Locale

internal fun sourceFilename(sourceName: String?): String? =
    sourceName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf(String::isNotEmpty)

internal fun filenameStem(sourceName: String?, extension: String): String? =
    sourceFilename(sourceName)?.let { name ->
        if (name.endsWith(".$extension", ignoreCase = true)) name.dropLast(extension.length + 1).trimEnd() else name
    }?.takeIf(String::isNotEmpty)

internal fun currentFilename(sourceName: String?, extension: String): String? {
    val name = sourceFilename(sourceName) ?: return null
    if (filenameStem(name, extension) == null) return null
    return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
}

private val forbidden = Regex("""[\p{Cc}<>:"/\\|?*]""")
private val reserved = Regex("""(?i)CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]""")

internal fun validatedBookFilename(input: String, extension: String): String? {
    if (forbidden.containsMatchIn(input)) return null
    var stem = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
    while (stem.endsWith(".$extension", ignoreCase = true)) {
        stem = stem.dropLast(extension.length + 1).trimEnd()
    }
    if (stem.isBlank() || stem.endsWith('.') || reserved.matches(stem.substringBefore('.').trimEnd())) return null
    return "$stem.$extension".takeIf { it.toByteArray(Charsets.UTF_8).size <= 255 }
}

internal fun filenameCollisionKey(filename: String): String =
    Normalizer.normalize(filename, Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun suggestedExportName(sourceName: String?, title: String, extension: String, fallback: String): String {
    val validated = filenameStem(sourceName, extension)?.let { validatedBookFilename(it, extension) }
    return currentFilename(sourceName, extension)?.takeIf { it.equals(validated, ignoreCase = true) }
        ?: validated
        ?: validatedBookFilename(title, extension)
        ?: validatedBookFilename(fallback, extension)
        ?: "Book.$extension"
}

internal class ExportFileNames(existingNames: Iterable<String>) {
    private val occupied = existingNames.mapTo(mutableSetOf(), ::filenameCollisionKey)
    private val nextSuffix = mutableMapOf<String, Int>()

    fun reserve(name: String) {
        occupied.add(filenameCollisionKey(name))
    }

    fun next(preferred: String, extension: String): String? {
        val key = filenameCollisionKey(preferred)
        if (occupied.add(key)) return preferred
        val stem = filenameStem(preferred, extension) ?: return null
        for (index in (nextSuffix[key] ?: 2)..10_000) {
            val suffix = " ($index).$extension"
            var base = stem
            while ((base + suffix).toByteArray(Charsets.UTF_8).size > 255) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            val candidate = base.trimEnd() + suffix
            if (occupied.add(filenameCollisionKey(candidate))) {
                nextSuffix[key] = index + 1
                return candidate
            }
        }
        return null
    }
}
