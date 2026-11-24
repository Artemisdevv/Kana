package com.artemis.ebookreader.data.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val signers: Set<String>,
)

internal interface UpdatePackageInfo {
    val sdk: Int
    val installed: ApkIdentity
    fun inspect(file: File): ApkIdentity?
}

internal fun verifyApkIdentity(candidate: ApkIdentity?, installed: ApkIdentity, release: KanaRelease, sdk: Int) {
    if (candidate == null || candidate.packageName != installed.packageName ||
        candidate.versionCode != release.code || candidate.versionCode <= installed.versionCode ||
        candidate.versionName != release.version || candidate.minSdk != release.minSdk || candidate.minSdk > sdk
    ) throw UpdateException(UpdateProblem.InvalidApk)
    if (installed.signers.isEmpty() || candidate.signers != installed.signers) throw UpdateException(UpdateProblem.Signing)
}

internal class UpdateFiles(
    private val directory: File,
    private val packages: UpdatePackageInfo,
    private val http: UpdateHttp,
) {
    private val readyDirectory = File(directory, "ready")
    private val apk = File(readyDirectory, "kana-update.apk")
    private val partial = File(directory, "download.part.apk")
    private val metadata = File(directory, "ready.properties")
    private val partialMetadata = File(directory, "ready.part")

    suspend fun recover(): KanaRelease? = withContext(Dispatchers.IO) {
        partial.delete()
        partialMetadata.delete()
        try {
            val saved = readMetadata()
            if (saved != null) {
                verifyFile(apk, saved.release, saved.sha256, currentCoroutineContext()::ensureActive)
                return@withContext saved.release
            }
        } catch (_: IOException) {
            // An incomplete promotion, modified file, or already-installed version is stale.
        } catch (_: IllegalArgumentException) {
            // Invalid local metadata is stale as well.
        }
        removeReady()
        null
    }

    suspend fun download(release: KanaRelease, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        if (!validRelease(release)) throw UpdateException(UpdateProblem.ReleaseMetadata)
        if (!readyDirectory.isDirectory && !readyDirectory.mkdirs()) throw UpdateException(UpdateProblem.Storage)
        partial.delete()
        if (directory.usableSpace < release.size + 16L * 1024 * 1024) throw UpdateException(UpdateProblem.Storage)
        try {
            val digest = withTimeoutOrNull(30 * 60_000L) {
                http.get(release.url, api = false) { input, length, checkActive ->
                    if (length >= 0 && length != release.size) throw UpdateException(UpdateProblem.Incomplete)
                    val saved = openPartial().use { stream ->
                        val hash = copyUpdate(input, stream, release.size, progress, checkActive)
                        diskWrite { stream.fd.sync() }
                        hash
                    }
                    verifyDigest(saved, release.digest)
                    saved
                }
            } ?: throw UpdateException(UpdateProblem.Network)
            currentCoroutineContext().ensureActive()
            verifyApkIdentity(packages.inspect(partial), packages.installed, release, packages.sdk)
            currentCoroutineContext().ensureActive()
            promote(release, digest)
            apk
        } finally {
            partial.delete()
            partialMetadata.delete()
        }
    }

    suspend fun installable(release: KanaRelease): File = withContext(Dispatchers.IO) {
        try {
            val saved = readMetadata()?.takeIf { it.release == release } ?: throw UpdateException(UpdateProblem.InvalidApk)
            verifyFile(apk, release, saved.sha256, currentCoroutineContext()::ensureActive)
            apk
        } catch (error: IOException) {
            removeReady()
            throw error
        } catch (_: IllegalArgumentException) {
            removeReady()
            throw UpdateException(UpdateProblem.InvalidApk)
        }
    }

    private fun verifyFile(file: File, release: KanaRelease, savedDigest: String, checkActive: () -> Unit) {
        if (!file.isFile || file.length() != release.size) throw UpdateException(UpdateProblem.Incomplete)
        val digest = file.inputStream().use { copyUpdate(it, null, release.size, {}, checkActive) }
        verifyDigest(digest, savedDigest)
        verifyDigest(digest, release.digest)
        checkActive()
        verifyApkIdentity(packages.inspect(file), packages.installed, release, packages.sdk)
    }

    private fun openPartial(): FileOutputStream = diskWrite { FileOutputStream(partial) }

    private fun promote(release: KanaRelease, digest: String) {
        diskWrite {
            val properties = Properties().apply {
                setProperty("tag", release.tag)
                setProperty("code", release.code.toString())
                setProperty("minSdk", release.minSdk.toString())
                setProperty("size", release.size.toString())
                setProperty("notes", release.notes)
                release.digest?.let { setProperty("digest", it) }
                setProperty("sha256", digest)
            }
            FileOutputStream(partialMetadata).use { stream ->
                properties.store(stream, null)
                stream.fd.sync()
            }
            if (!partial.renameTo(apk)) throw IOException("Could not move verified APK")
            if (!partialMetadata.renameTo(metadata)) {
                removeReady()
                throw IOException("Could not save update metadata")
            }
        }
    }

    private data class SavedUpdate(val release: KanaRelease, val sha256: String)

    private fun readMetadata(): SavedUpdate? {
        if (!metadata.isFile || metadata.length() > 200_000) return null
        val properties = Properties().apply { metadata.inputStream().use(::load) }
        val release = KanaRelease(
            properties.getProperty("tag") ?: return null,
            properties.getProperty("code")?.toLongOrNull() ?: return null,
            properties.getProperty("minSdk")?.toIntOrNull() ?: return null,
            properties.getProperty("size")?.toLongOrNull() ?: return null,
            properties.getProperty("digest"),
            properties.getProperty("notes", "").take(20_000),
        )
        val hash = properties.getProperty("sha256")?.takeIf(sha256Pattern::matches) ?: return null
        return SavedUpdate(release, hash).takeIf { validRelease(release) }
    }

    private fun removeReady() {
        metadata.delete()
        apk.delete()
    }

    companion object {
        const val directoryName = "kana-updates"
    }
}

internal fun copyUpdate(
    input: InputStream,
    output: OutputStream?,
    expectedBytes: Long,
    progress: (Long) -> Unit,
    checkActive: () -> Unit,
): String {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var lastProgress = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > expectedBytes) throw UpdateException(UpdateProblem.Incomplete)
        if (output != null) diskWrite { output.write(buffer, 0, count) }
        sha256.update(buffer, 0, count)
        if (total - lastProgress >= 256 * 1024 || total == expectedBytes) {
            progress(total)
            lastProgress = total
        }
    }
    if (total != expectedBytes) throw UpdateException(UpdateProblem.Incomplete)
    return sha256.digest().hex()
}

private fun <T> diskWrite(write: () -> T): T = try {
    write()
} catch (_: IOException) {
    throw UpdateException(UpdateProblem.Storage)
}

internal fun verifyDigest(actual: String, expected: String?) {
    if (expected != null && actual != expected) throw UpdateException(UpdateProblem.Digest)
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
