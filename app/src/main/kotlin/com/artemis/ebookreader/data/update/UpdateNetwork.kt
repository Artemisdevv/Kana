package com.artemis.ebookreader.data.update

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface UpdateHttp {
    suspend fun <T> get(
        url: URI,
        api: Boolean,
        read: (input: InputStream, length: Long, checkActive: () -> Unit) -> T,
    ): T
}

internal class GithubUpdateHttp : UpdateHttp {
    override suspend fun <T> get(
        url: URI,
        api: Boolean,
        read: (InputStream, Long, () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpsURLConnection?>()
            continuation.invokeOnCancellation {
                connection.getAndSet(null)?.let { current ->
                    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { current.disconnect() })
                }
            }
            val checkActive = {
                if (!continuation.isActive) throw CancellationException("Update request cancelled")
            }
            val result = runCatching { request(url, api, connection, checkActive, read) }
            continuation.resumeWith(result)
        }
    }

    private fun <T> request(
        url: URI,
        api: Boolean,
        activeConnection: AtomicReference<HttpsURLConnection?>,
        checkActive: () -> Unit,
        read: (InputStream, Long, () -> Unit) -> T,
    ): T {
        var destination = url
        repeat(5) {
            if (!allowedUpdateUrl(destination, api)) throw UpdateException(UpdateProblem.Network)
            val connection = destination.toURL().openConnection() as HttpsURLConnection
            activeConnection.set(connection)
            try {
                checkActive()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Kana-Android-Updater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                if (status == 200) {
                    return connection.inputStream.use { read(it, connection.contentLengthLong, checkActive) }
                }
                if (!api && status in redirectCodes) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateProblem.Network)
                    destination = try {
                        destination.resolve(location)
                    } catch (_: IllegalArgumentException) {
                        throw UpdateException(UpdateProblem.Network)
                    }
                } else {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    val retry = connection.getHeaderField("Retry-After")
                    val message = if (api && status == 403 && remaining != "0" && retry == null) {
                        connection.errorStream?.use { stream ->
                            val sample = ByteArray(4096)
                            val count = stream.read(sample)
                            if (count > 0) String(sample, 0, count, Charsets.UTF_8) else null
                        }
                    } else null
                    throw UpdateException(githubHttpProblem(status, remaining, retry, message))
                }
            } finally {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
        throw UpdateException(UpdateProblem.Network)
    }

    private companion object {
        val redirectCodes = setOf(301, 302, 303, 307, 308)
    }
}

internal fun allowedUpdateUrl(url: URI, api: Boolean): Boolean {
    if (url.scheme != "https" || url.port !in listOf(-1, 443) || url.rawUserInfo != null || url.rawFragment != null) return false
    return if (api) url.host == "api.github.com" else url.host in assetHosts
}

private val assetHosts = setOf(
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com",
)

internal fun githubHttpProblem(status: Int, remaining: String?, retryAfter: String?, message: String? = null): UpdateProblem =
    if (status == 429 || status == 403 &&
        (remaining == "0" || retryAfter != null || message?.contains("rate limit", ignoreCase = true) == true)
    ) UpdateProblem.RateLimited
    else UpdateProblem.Network

internal class GithubReleases(private val http: UpdateHttp, private val timeoutMillis: Long = 30_000) {
    suspend fun latest(installedCode: Long, sdk: Int): KanaRelease? {
        val pages = withTimeoutOrNull(timeoutMillis) { pages() } ?: throw UpdateException(UpdateProblem.Network)
        return selectRelease(pages, installedCode, sdk)
    }

    private suspend fun pages(): List<ReleasePage> {
        val pages = mutableListOf<ReleasePage>()
        for (number in 1..10) {
            val page = http.get(KanaRepository.releasePage(number), api = true) { input, length, checkActive ->
                parseReleasePage(input.readReleaseJson(length, checkActive))
            }
            pages.add(page)
            if (page.count < 100) return pages
        }
        throw UpdateException(UpdateProblem.ReleaseMetadata)
    }
}

private fun InputStream.readReleaseJson(length: Long, checkActive: () -> Unit): String {
    val limit = 8 * 1024 * 1024
    if (length > limit) throw UpdateException(UpdateProblem.ReleaseMetadata)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkActive()
        val read = read(buffer)
        if (read == -1) break
        if (output.size() + read > limit) throw UpdateException(UpdateProblem.ReleaseMetadata)
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
