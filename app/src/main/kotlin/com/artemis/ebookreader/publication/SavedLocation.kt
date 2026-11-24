package com.artemis.ebookreader.publication

import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression

internal data class SavedLocation(
    val locator: Locator,
    val reflowAnchor: Locator? = null,
    val layout: String? = null,
) {
    fun encode(sha256: String): String = JSONObject().apply {
        put("kind", "readium-epub")
        put("version", 1)
        put("assetSha256", sha256)
        put("locator", locator.toJSON())
        put("reflowAnchor", reflowAnchor?.toJSON())
        put("layout", layout)
    }.toString()

    companion object {
        fun decode(json: String?, sha256: String): SavedLocation? {
            if (json == null || json.length > 64 * 1024) return null
            return try {
                val envelope = JSONObject(json)
                if (envelope.optString("kind") != "readium-epub" ||
                    envelope.optInt("version") != 1 ||
                    envelope.optString("assetSha256") != sha256
                ) return null
                val locator = Locator.fromJSON(envelope.optJSONObject("locator")) ?: return null
                if (locator.locations.progression?.let { it !in 0.0..1.0 } == true) return null
                SavedLocation(
                    locator,
                    Locator.fromJSON(envelope.optJSONObject("reflowAnchor")),
                    envelope.optString("layout").takeIf(String::isNotBlank),
                )
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

internal suspend fun Publication.resumeLocator(
    saved: SavedLocation?,
    layout: String,
    fallbackProgression: Double?,
): Locator? {
    if (saved != null) {
        val href = saved.locator.href.removeFragment()
        if (readingOrder.any { it.url().removeFragment().isEquivalent(href) }) {
            val anchor = saved.reflowAnchor
            if (saved.layout != layout && anchor != null && anchor.href.removeFragment().isEquivalent(href)) {
                return anchor
            }
            return saved.locator
        }
    }
    return fallbackProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { locateProgression(it) }
}
