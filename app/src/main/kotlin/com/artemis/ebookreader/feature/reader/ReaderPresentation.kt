package com.artemis.ebookreader.feature.reader

import com.artemis.ebookreader.data.preferences.ReaderAlignment
import com.artemis.ebookreader.data.preferences.ReaderFont
import com.artemis.ebookreader.data.preferences.ReaderLayout
import com.artemis.ebookreader.data.preferences.ReaderPalette
import com.artemis.ebookreader.data.preferences.ReaderWeight
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.DomRange

internal fun ReaderLayout.effectivePresentation(): ReaderLayout = if (publisherStyles) {
    copy(lineHeight = null, paragraphSpacing = null, alignment = ReaderAlignment.Publisher)
} else this

@OptIn(ExperimentalReadiumApi::class)
internal fun ReaderLayout.toNavigatorPreferences(palette: ReaderPalette, fontScale: Double): EpubPreferences {
    val layout = effectivePresentation()
    return EpubPreferences(
        theme = if (palette.dark) Theme.DARK else Theme.LIGHT,
        backgroundColor = Color(palette.background),
        textColor = Color(palette.text),
        fontFamily = when (layout.font) {
            ReaderFont.Publisher -> null
            ReaderFont.Serif -> FontFamily.SERIF
            ReaderFont.SansSerif -> FontFamily.SANS_SERIF
            ReaderFont.Monospace -> FontFamily.MONOSPACE
            ReaderFont.AccessibleDfA -> FontFamily.ACCESSIBLE_DFA
            ReaderFont.Duospace -> FontFamily.IA_WRITER_DUOSPACE
            ReaderFont.OpenDyslexic -> FontFamily.OPEN_DYSLEXIC
            else -> FontFamily(checkNotNull(layout.font.bundled).family)
        },
        fontSize = fontScale * layout.fontSize / 100.0,
        fontWeight = when (layout.fontWeight) {
            ReaderWeight.Publisher -> null
            ReaderWeight.Regular -> 1.0
            ReaderWeight.Medium -> 1.25
            ReaderWeight.Semibold -> 1.5
        },
        lineHeight = layout.lineHeight?.div(100.0),
        paragraphSpacing = layout.paragraphSpacing?.div(100.0),
        pageMargins = layout.horizontalMargins / 100.0,
        publisherStyles = layout.publisherStyles,
        scroll = layout.scroll,
        textAlign = when (layout.alignment) {
            ReaderAlignment.Publisher -> null
            ReaderAlignment.Left -> TextAlign.LEFT
            ReaderAlignment.Justified -> TextAlign.JUSTIFY
        },
    )
}

/** Uses a quote at the visible edge of the page, even when the paragraph began on an earlier page. */
@OptIn(ExperimentalReadiumApi::class)
internal suspend fun EpubNavigatorFragment.visiblePassage(
    locator: Locator,
    includeBookmarkPoint: Boolean = false,
): Locator? = withTimeoutOrNull(700) {
    val passage = evaluateJavascript("$VisiblePassageScript($includeBookmarkPoint)")?.let { result ->
        try {
            val data = JSONObject(result)
            val text = data.getString("highlight")
            val point = DomRange.Point.fromJSON(data.optJSONObject("point"))
            val locations = if (point == null) emptyMap() else mapOf(
                "cssSelector" to point.cssSelector,
                "domRange" to mapOf("start" to mapOf(
                    "cssSelector" to point.cssSelector,
                    "textNodeIndex" to point.textNodeIndex,
                    "charOffset" to (point.charOffset ?: 0),
                )),
            )
            locator.copy(
                locations = locator.locations.copy(fragments = emptyList(), otherLocations = locations),
                text = Locator.Text(before = data.optString("before"), highlight = text, after = data.optString("after")),
            ).takeIf { text.isNotBlank() }
        } catch (_: JSONException) {
            null
        }
    }
    passage ?: firstVisibleElementLocator()?.takeIf {
        it.href.removeFragment().isEquivalent(locator.href.removeFragment()) &&
            (it.text.highlight?.length ?: 0) in 1..4_000
    }
}

private val VisiblePassageScript = """
    ((bookmark) => {
        const body = document.body;
        if (!body || getComputedStyle(body).writingMode !== 'horizontal-tb') return null;
        const first = readium.findFirstVisibleLocator();
        const selector = first && first.locations.cssSelector;
        const root = (selector && document.querySelector(selector)) || body;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        const range = document.createRange();
        const visible = () => Array.from(range.getClientRects()).some(rect =>
            rect.width > 0 && rect.height > 0 && rect.right > 1 && rect.left < innerWidth - 1 &&
            rect.bottom > 1 && rect.top < innerHeight - 1
        );
        for (let i = 0, node; i < 400 && (node = walker.nextNode()); i++) {
            const text = node.data;
            if (!text.trim()) continue;
            range.selectNodeContents(node);
            if (!visible()) continue;
            let start = 0, end = text.length;
            while (start + 1 < end) {
                const mid = Math.floor((start + end) / 2);
                range.setEnd(node, mid);
                if (visible()) end = mid;
                else start = mid;
            }
            let offset = Math.max(0, end - 1);
            if (bookmark) while (offset < text.length && /\s/.test(text[offset])) offset++;
            const highlight = text.slice(offset, offset + 100).trimEnd();
            if (highlight.trim().length < 4) continue;
            const passage = { highlight, before: text.slice(Math.max(0, offset - 70), offset),
                after: text.slice(offset + highlight.length, offset + highlight.length + 70) };
            if (bookmark) {
                const path = [];
                let parent = node.parentElement;
                let textNodeIndex = 0;
                for (let sibling = node.previousSibling; sibling; sibling = sibling.previousSibling) {
                    if (sibling.nodeType === Node.TEXT_NODE) textNodeIndex++;
                }
                while (parent && parent !== body && path.length < 48) {
                    let index = 1;
                    for (let sibling = parent.previousElementSibling; sibling; sibling = sibling.previousElementSibling) index++;
                    path.unshift(':nth-child(' + index + ')');
                    parent = parent.parentElement;
                }
                if (parent === body) {
                    passage.point = { cssSelector: ['body', ...path].join(' > '), textNodeIndex, charOffset: offset };
                }
            }
            return passage;
        }
        return null;
    })
""".trimIndent()
