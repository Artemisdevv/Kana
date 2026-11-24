package com.artemis.ebookreader.data.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal val testApkBytes = "signed package fixture".toByteArray()
internal val testDigest = MessageDigest.getInstance("SHA-256").digest(testApkBytes).hex()
internal val testRelease = KanaRelease("v1.2.0", 12, 24, testApkBytes.size.toLong(), testDigest, "First update")
internal val testIdentity = ApkIdentity("com.artemis.ebookreader", 2, "0.2.0", 24, setOf("test-signer"))

internal fun testReleaseJson(
    release: KanaRelease = testRelease,
    draft: Boolean = false,
    prerelease: Boolean = false,
    assetName: String = release.fileName,
    url: String = release.url.toString(),
    digest: String? = release.digest?.let { "sha256:$it" },
) = buildJsonObject {
    put("tag_name", release.tag)
    put("draft", draft)
    put("prerelease", prerelease)
    put("body", release.notes)
    put("assets", buildJsonArray {
        add(buildJsonObject { put("name", "app-debug.apk") })
        add(buildJsonObject { put("name", "Kana-${release.tag}-performance.apk") })
        add(buildJsonObject {
            put("name", assetName)
            put("size", release.size)
            put("state", "uploaded")
            put("browser_download_url", url)
            digest?.let { put("digest", it) }
        })
    })
}

internal class FixtureHttp(
    private val response: suspend (URI, Boolean) -> ByteArray,
    private val length: Long = -1,
) : UpdateHttp {
    override suspend fun <T> get(url: URI, api: Boolean, read: (InputStream, Long, () -> Unit) -> T): T =
        ByteArrayInputStream(response(url, api)).use { read(it, length) {} }
}

internal class FixturePackages : UpdatePackageInfo {
    override val installed = testIdentity
    override val sdk = 36
    var candidate: ApkIdentity? = installed.copy(versionCode = testRelease.code, versionName = testRelease.version)
    var inspections = 0
    override fun inspect(file: File): ApkIdentity? {
        inspections++
        return candidate
    }
}

class KanaUpdateTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun selectsByNumericCodeAndCompatibleSdkAcrossPages() {
        val low = testRelease.copy(tag = "v99.0.0", code = 9)
        val high = testRelease.copy(tag = "v1.0.0", code = 100)
        val future = testRelease.copy(code = 101, minSdk = 37)
        val page1 = parseReleasePage("[${testReleaseJson(low)},${testReleaseJson(future)}]")
        val page2 = parseReleasePage("[${testReleaseJson(high)},${testReleaseJson(testRelease.copy(code = 999), draft = true)}," +
            "${testReleaseJson(testRelease.copy(code = 1000), prerelease = true)}]")
        assertEquals(high, selectRelease(listOf(page1, page2), installedCode = 2, sdk = 36))
        assertEquals(future, selectRelease(listOf(page1, page2), installedCode = 100, sdk = 37))
        assertNull(selectRelease(listOf(page1, page2), installedCode = 100, sdk = 36))
        assertNull(selectRelease(listOf(parseReleasePage("[]")), installedCode = 2, sdk = 36))
    }

    @Test fun validatesReleaseMetadataAndExactAsset() {
        assertEquals(listOf(testRelease), parseReleasePage("[${testReleaseJson()}]").releases)
        assertEquals(listOf(testRelease.copy(digest = null)), parseReleasePage("[${testReleaseJson(digest = null)}]").releases)
        val bad = listOf(
            """{"draft":false,"prerelease":false,"tag_name":"v1.0.0","assets":[]}""",
            testReleaseJson(assetName = "Kana-v1.2.0-vc12-minsdk24-debug.apk").toString(),
            testReleaseJson(url = "https://example.test/update.apk").toString(),
            testReleaseJson(digest = "sha256:abc").toString(),
            testReleaseJson(release = testRelease.copy(tag = "v1.2.0-rc")).toString(),
            testReleaseJson(release = testRelease.copy(size = 0)).toString(),
        )
        for (row in bad) {
            val page = parseReleasePage("[$row]")
            assertTrue(page.releases.isEmpty())
            assertProblem(UpdateProblem.ReleaseMetadata) { selectRelease(listOf(page), 2, 36) }
        }
        assertProblem(UpdateProblem.ReleaseMetadata) { parseReleasePage("""{"message":"not an array"}""") }
    }

    @Test fun reportsNetworkTimeoutAndRateLimits() = runBlocking {
        assertEquals(UpdateProblem.RateLimited, githubHttpProblem(429, null, null))
        assertEquals(UpdateProblem.RateLimited, githubHttpProblem(403, "0", null))
        assertEquals(UpdateProblem.RateLimited, githubHttpProblem(403, "40", "60"))
        assertEquals(UpdateProblem.RateLimited, githubHttpProblem(403, "40", null, "secondary rate limit"))
        assertEquals(UpdateProblem.Network, githubHttpProblem(403, "40", null))
        assertFalse(allowedUpdateUrl(URI.create("http://github.com/Kana"), api = false))
        assertFalse(allowedUpdateUrl(URI.create("https://github.com@evil.test/Kana"), api = false))
        assertTrue(allowedUpdateUrl(URI.create("https://release-assets.githubusercontent.com/file?token=example"), api = false))
        assertThrows(IOException::class.java) {
            runBlocking { GithubReleases(FixtureHttp({ _, _ -> throw IOException() })).latest(2, 36) }
        }
        assertProblem(UpdateProblem.Network) {
            runBlocking { GithubReleases(FixtureHttp({ _, _ -> awaitCancellation() }), timeoutMillis = 10).latest(2, 36) }
        }
        assertProblem(UpdateProblem.RateLimited) {
            runBlocking {
                GithubReleases(FixtureHttp({ _, _ -> throw UpdateException(UpdateProblem.RateLimited) })).latest(2, 36)
            }
        }
        assertNull(GithubReleases(FixtureHttp({ _, _ -> "[]".toByteArray() })).latest(2, 36))
    }

    @Test fun verifiesPackageIdentityAndDigest() {
        val packages = FixturePackages()
        val good = checkNotNull(packages.candidate)
        verifyApkIdentity(good, packages.installed, testRelease, packages.sdk)
        for (candidate in listOf(null, good.copy(packageName = "other"), good.copy(versionCode = 2),
            good.copy(versionCode = 13), good.copy(versionName = "1.2.1"), good.copy(minSdk = 38))
        ) {
            assertProblem(UpdateProblem.InvalidApk) { verifyApkIdentity(candidate, packages.installed, testRelease, 36) }
        }
        assertProblem(UpdateProblem.Signing) {
            verifyApkIdentity(good.copy(signers = setOf("other")), packages.installed, testRelease, 36)
        }
        assertProblem(UpdateProblem.Digest) { verifyDigest(testDigest, "0".repeat(64)) }
    }

    @Test fun recoversOnlyVerifiedFilesAndReverifiesBeforeInstall() = runBlocking {
        val directory = folder.newFolder()
        val packages = FixturePackages()
        val http = FixtureHttp({ url, api ->
            assertFalse(api)
            assertEquals(testRelease.url, url)
            testApkBytes
        }, testRelease.size)
        val completed = UpdateFiles(directory, packages, http).download(testRelease) {}
        val restarted = UpdateFiles(directory, packages, http)
        assertEquals(testRelease, restarted.recover())
        assertEquals(completed, restarted.installable(testRelease))
        assertEquals(3, packages.inspections)
        completed.writeBytes(testApkBytes.reversedArray())
        assertProblem(UpdateProblem.Digest) { runBlocking { restarted.installable(testRelease) } }
        assertNull(restarted.recover())
        assertFalse(completed.exists())
    }

    @Test fun discardsCancelledIncompleteAndInvalidDownloads() = runBlocking {
        val directory = folder.newFolder()
        val packages = FixturePackages()
        val files = UpdateFiles(directory, packages, FixtureHttp({ _, _ -> testApkBytes }))
        try {
            files.download(testRelease) { throw CancellationException("test cancellation") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(File(directory, "download.part.apk").exists())
        }
        val incomplete = UpdateFiles(directory, packages, FixtureHttp({ _, _ -> testApkBytes.copyOf(4) }))
        assertProblem(UpdateProblem.Incomplete) { runBlocking { incomplete.download(testRelease) {} } }
        assertProblem(UpdateProblem.Digest) {
            runBlocking { files.download(testRelease.copy(digest = "a".repeat(64))) {} }
        }
        packages.candidate = null
        assertProblem(UpdateProblem.InvalidApk) { runBlocking { files.download(testRelease) {} } }
        assertNull(files.recover())
        assertFalse(File(directory, "download.part.apk").exists())
    }
}

private fun assertProblem(problem: UpdateProblem, action: () -> Unit) {
    assertEquals(problem, assertThrows(UpdateException::class.java, action).problem)
}
