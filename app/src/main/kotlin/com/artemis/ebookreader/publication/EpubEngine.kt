package com.artemis.ebookreader.publication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.max
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

internal enum class BookProblem { Unsupported, Corrupt, Protected, TooLarge, Unreadable, Missing }

internal class BookFailure(val problem: BookProblem) : IOException(problem.name)

@OptIn(ExperimentalReadiumApi::class)
internal class EpubEngine(context: Context) {
    private val offlineHttp = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
            Try.failure(HttpError.IO(IOException("Remote EPUB resources are disabled.")))
    }
    private val assets = AssetRetriever(context.contentResolver, offlineHttp)
    private val opener = PublicationOpener(EpubParser(httpClient = offlineHttp))

    suspend fun open(file: File, sourceName: String?, paginated: Boolean = false): Publication {
        checkArchive(file, sourceName)
        val asset = assets.retrieve(file.toUrl(isDirectory = false)).getOrElse { throw BookFailure(BookProblem.Corrupt) }
        var publication: Publication? = null
        try {
            if (!asset.format.conformsTo(Specification.Epub)) {
                throw BookFailure(BookProblem.Corrupt)
            }
            val opened = opener.open(
                asset,
                allowUserInteraction = false,
                onCreatePublication = { if (paginated) addPaginatedChapterBreaks() },
            )
                .getOrElse { throw BookFailure(BookProblem.Corrupt) }
            publication = opened
            if (opened.isRestricted) throw BookFailure(BookProblem.Protected)
            val first = opened.readingOrder.firstOrNull() ?: throw BookFailure(BookProblem.Corrupt)
            opened.get(first)?.use { resource ->
                if (resource.length().getOrNull()?.let { it > 0 } != true) {
                    throw BookFailure(BookProblem.Corrupt)
                }
            } ?: throw BookFailure(BookProblem.Corrupt)
            return opened
        } catch (failure: Exception) {
            if (publication == null) asset.close() else publication.close()
            throw failure
        }
    }

    suspend fun thumbnail(publication: Publication): Bitmap? {
        val cover = (publication.resources + publication.readingOrder)
            .firstOrNull { "cover" in it.rels } ?: return null
        val bytes = publication.get(cover)?.use { resource ->
            val length = resource.length().getOrNull() ?: return null
            if (length !in 1..MaxCoverBytes) return null
            resource.read(0L until length).getOrNull()
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun checkArchive(file: File, sourceName: String?) {
        val invalidFormat = if (sourceName?.endsWith(".epub", ignoreCase = true) == true) {
            BookProblem.Corrupt
        } else {
            BookProblem.Unsupported
        }
        try {
            ZipFile(file).use { archive ->
                if (archive.getEntry("META-INF/container.xml") == null) throw BookFailure(invalidFormat)
                if (archive.size() > 10_000) throw BookFailure(BookProblem.TooLarge)
                var expandedBytes = 0L
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val extension = entry.name.substringAfterLast('.').lowercase()
                    val limit = if (extension in TextExtensions) MaxTextBytes else MaxEntryBytes
                    if (entry.size !in 0..limit || entry.size > MaxExpandedBytes - expandedBytes) {
                        throw BookFailure(BookProblem.TooLarge)
                    }
                    expandedBytes += entry.size
                }
            }
        } catch (_: ZipException) {
            throw BookFailure(invalidFormat)
        }
    }

    private companion object {
        const val MaxCoverBytes = 8L * 1024 * 1024
        const val MaxTextBytes = 8L * 1024 * 1024
        const val MaxEntryBytes = 48L * 1024 * 1024
        const val MaxExpandedBytes = 1536L * 1024 * 1024
        val TextExtensions = setOf("xml", "opf", "ncx", "html", "xhtml", "htm", "svg", "css")
    }
}
