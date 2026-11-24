package com.artemis.ebookreader.publication

import org.json.JSONArray
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.domRange
import kotlin.math.abs
import kotlin.math.roundToInt

internal val SavedLocation.bookmarkPoint get() = reflowAnchor?.locations?.domRange?.start

internal fun SavedLocation.bookmarkKey(): String {
    val key = JSONArray().put(locator.href.removeFragment().normalize().toString())
    val point = bookmarkPoint
    val progression = locator.locations.progression?.takeIf { it in 0.0..1.0 }
    return when {
        point != null -> key.put("text").put(point.cssSelector).put(point.textNodeIndex).put(point.charOffset ?: 0)
        progression != null -> key.put("progression").put((progression * 1_000_000).roundToInt())
        else -> key.put("native").put(locator.locations.position)
            .put(JSONArray(listOfNotNull(locator.href.fragment) + locator.locations.fragments))
    }.toString()
}

/** A conservative fallback for fixed-layout or image-only passages with no usable text point. */
internal fun Locator.matchesBookmarkFallback(saved: Locator): Boolean {
    if (!href.removeFragment().isEquivalent(saved.href.removeFragment())) return false
    val here = locations.progression
    val there = saved.locations.progression
    if (here != null && there != null) return abs(here - there) <= 0.0002
    val fragments = listOfNotNull(href.fragment) + locations.fragments
    val savedFragments = listOfNotNull(saved.href.fragment) + saved.locations.fragments
    if (fragments.isNotEmpty() || savedFragments.isNotEmpty()) return fragments == savedFragments
    return locations.position == saved.locations.position
}
