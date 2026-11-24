package com.artemis.ebookreader.data.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val MaxBookBytes = 512L * 1024 * 1024
internal val Sha256Pattern = Regex("[a-f0-9]{64}")

internal data class CopiedBook(val sha256: String, val size: Long)

internal class BookTooLarge : IOException()

internal fun copyAndHash(
    source: InputStream,
    destination: OutputStream? = null,
    limit: Long = MaxBookBytes,
    checkCancellation: () -> Unit = {},
): CopiedBook {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        checkCancellation()
        val count = source.read(bytes)
        if (count < 0) break
        if (count > limit - size) throw BookTooLarge()
        digest.update(bytes, 0, count)
        destination?.write(bytes, 0, count)
        size += count
    }
    return CopiedBook(digest.digest().toHexString(), size)
}

internal fun bookFilename(sourceName: String?): String? = filenameStem(sourceName, "epub")

internal fun bookTitle(metadataTitle: String?, sourceName: String?, fallback: String? = null): String? =
    metadataTitle?.trim()?.takeUnless(String::isBlank)
        ?: bookFilename(sourceName)
        ?: fallback
