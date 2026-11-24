package com.artemis.ebookreader.feature.reader

import com.artemis.ebookreader.data.library.ResolvedBookmark
import com.artemis.ebookreader.publication.bookmarkPoint
import com.artemis.ebookreader.publication.matchesBookmarkFallback
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalReadiumApi::class)
internal suspend fun EpubNavigatorFragment.visibleBookmark(
    locator: Locator,
    bookmarks: List<ResolvedBookmark>,
): ResolvedBookmark? {
    val href = locator.href.removeFragment()
    val candidates = bookmarks.filter { it.location.locator.href.removeFragment().isEquivalent(href) }
    if (candidates.isEmpty()) return null
    val points = JSONArray()
    for (candidate in candidates) {
        val point = candidate.location.bookmarkPoint
        val quote = candidate.location.reflowAnchor?.text?.highlight
        points.put(if (point != null && !quote.isNullOrEmpty()) {
            point.toJSON().put("excerpt", quote.take(24))
        } else JSONObject.NULL)
    }
    val result = if (candidates.any { it.location.bookmarkPoint != null }) {
        withTimeoutOrNull(450) { evaluateJavascript("$VisibleBookmarkPoints($points)") }
    } else null
    val visibility = try {
        result?.let(::JSONArray)
    } catch (_: JSONException) {
        null
    }
    // A resolved point outside the viewport must not match on approximate progression.
    return candidates.withIndex().firstOrNull { (index, bookmark) ->
        when (visibility?.optInt(index, -1)) {
            1 -> true
            0 -> false
            else -> locator.matchesBookmarkFallback(bookmark.location.locator)
        }
    }?.value
}

private val VisibleBookmarkPoints = """
    ((points) => points.map(point => {
        if (!point) return -1;
        try {
            const element = document.querySelector(point.cssSelector);
            if (!element) return -1;
            const text = Array.from(element.childNodes).filter(node => node.nodeType === Node.TEXT_NODE)[point.textNodeIndex];
            const offset = point.charOffset || 0;
            if (!text || !text.data.startsWith(point.excerpt, offset)) return -1;
            const range = document.createRange();
            range.setStart(text, offset);
            range.setEnd(text, offset + (text.data.codePointAt(offset) > 0xFFFF ? 2 : 1));
            return Array.from(range.getClientRects()).some(rect =>
                rect.width > 0 && rect.height > 0 && rect.right > 1 && rect.left < innerWidth - 1 &&
                rect.bottom > 1 && rect.top < innerHeight - 1
            ) ? 1 : 0;
        } catch (_) {
            return -1;
        }
    }))
""".trimIndent()
