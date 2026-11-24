package com.artemis.ebookreader.data.update

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object KanaRepository {
    const val slug = "Artemisdevv/Kana"
    const val web = "https://github.com/$slug"
    const val releases = "$web/releases"
    const val issues = "$web/issues/new"

    fun releasePage(page: Int): URI =
        URI.create("https://api.github.com/repos/$slug/releases?per_page=100&page=$page")

    fun asset(tag: String, name: String): URI = URI.create("$releases/download/$tag/$name")
}

internal enum class UpdateProblem { Network, RateLimited, ReleaseMetadata, Storage, Incomplete, InvalidApk, Signing, Digest }

internal class UpdateException(val problem: UpdateProblem) : java.io.IOException(problem.name)

internal data class KanaRelease(
    val tag: String,
    val code: Long,
    val minSdk: Int,
    val size: Long,
    val digest: String?,
    val notes: String,
) {
    val version: String get() = tag.removePrefix("v")
    val fileName: String get() = "Kana-$tag-vc$code-minsdk$minSdk-release.apk"
    val url: URI get() = KanaRepository.asset(tag, fileName)
}

private const val maxApkBytes = 512L * 1024 * 1024
private val stableTag = Regex("""v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)""")
private val releaseApk = Regex("""Kana-(v[0-9]+\.[0-9]+\.[0-9]+)-vc([1-9][0-9]*)-minsdk([1-9][0-9]*)-release\.apk""")
internal val sha256Pattern = Regex("[a-f0-9]{64}")

internal fun validRelease(release: KanaRelease): Boolean =
    release.tag.length <= 40 && stableTag.matches(release.tag) &&
        release.code in 1..2_100_000_000L && release.minSdk in 1..10_000 &&
        release.size in 1..maxApkBytes && (release.digest == null || sha256Pattern.matches(release.digest))

internal data class ReleasePage(val releases: List<KanaRelease>, val malformedStable: Boolean, val count: Int)

internal fun parseReleasePage(body: String): ReleasePage {
    val rows = try {
        Json.parseToJsonElement(body) as? JsonArray
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw UpdateException(UpdateProblem.ReleaseMetadata)
    var malformed = false
    val releases = rows.mapNotNull { row ->
        val item = row as? JsonObject
        if (item?.boolean("draft") == true || item?.boolean("prerelease") == true) return@mapNotNull null
        val release = if (item?.boolean("draft") == false && item.boolean("prerelease") == false) parseRelease(item) else null
        if (release == null) malformed = true
        release
    }
    return ReleasePage(releases, malformed, rows.size)
}

internal fun selectRelease(pages: List<ReleasePage>, installedCode: Long, sdk: Int): KanaRelease? {
    val releases = pages.flatMap(ReleasePage::releases)
    if (releases.isEmpty() && pages.any(ReleasePage::malformedStable)) throw UpdateException(UpdateProblem.ReleaseMetadata)
    val compatible = releases.filter { it.code > installedCode && it.minSdk <= sdk }
    val newestCode = compatible.maxOfOrNull(KanaRelease::code) ?: return null
    return compatible.filter { it.code == newestCode }.distinct().singleOrNull()
        ?: throw UpdateException(UpdateProblem.ReleaseMetadata)
}

private fun parseRelease(item: JsonObject): KanaRelease? {
    val tag = item.string("tag_name") ?: return null
    val assets = item["assets"] as? JsonArray ?: return null
    val candidates = assets.mapNotNull { asset ->
        val fields = asset as? JsonObject ?: return@mapNotNull null
        val match = fields.string("name")?.let(releaseApk::matchEntire) ?: return@mapNotNull null
        (fields to match).takeIf { match.groupValues[1] == tag }
    }
    val (asset, match) = candidates.singleOrNull() ?: return null
    val digestField = asset["digest"]
    val digest = if (digestField == null || digestField == kotlinx.serialization.json.JsonNull) {
        null
    } else {
        asset.string("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:") ?: return null
    }
    val release = KanaRelease(
        tag, match.groupValues[2].toLongOrNull() ?: return null,
        match.groupValues[3].toIntOrNull() ?: return null,
        asset.primitive("size")?.takeUnless { it.isString }?.content?.toLongOrNull() ?: return null,
        digest, item.string("body").orEmpty().take(20_000),
    )
    if (!validRelease(release) || asset.string("state") != "uploaded" ||
        asset.string("browser_download_url") != release.url.toString()
    ) return null
    return release
}

private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
private fun JsonObject.string(key: String) = primitive(key)?.takeIf { it.isString }?.content
private fun JsonObject.boolean(key: String) = primitive(key)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
