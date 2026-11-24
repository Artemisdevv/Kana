package com.artemis.ebookreader.publication

import android.util.Xml
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

@OptIn(ExperimentalReadiumApi::class)
internal fun Publication.Builder.addPaginatedChapterBreaks() {
    if (manifest.metadata.layout == Layout.FIXED) return
    val entries = manifest.tableOfContents.contentsWithDepth()
        .filter { (link, _) -> link.url().fragment != null }
        .groupBy { (link, _) -> link.url().removeFragment().normalize() }
    val candidates = manifest.readingOrder.filter { it.mediaType == MediaType.XHTML }
        .mapNotNull { link ->
            val href = link.url().normalize()
            val targets = entries[href]?.takeIf { it.size in 2..MaxAnchors } ?: return@mapNotNull null
            href to targets.associate { (entry, depth) -> requireNotNull(entry.url().fragment) to depth }
        }.toMap()
    if (candidates.isEmpty()) return

    container = TransformingContainer(container) { url, resource ->
        val anchors = candidates[url.normalize()] ?: return@TransformingContainer resource
        resource.map { bytes -> Try.success(withChapterBreaks(bytes, anchors)) }
    }
}

internal fun List<Link>.contentsWithDepth(): List<Pair<Link, Int>> = buildList {
    fun visit(links: List<Link>, depth: Int) {
        for (link in links) {
            add(link to depth)
            if (depth < 32) visit(link.children, depth + 1)
        }
    }
    visit(this@contentsWithDepth, 0)
}

internal fun withChapterBreaks(bytes: ByteArray, anchors: Map<String, Int>): ByteArray {
    if (bytes.size > MaxXhtmlBytes) return bytes
    val xhtml = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        return bytes
    }
    val head = HeadStart.findAll(xhtml).take(2).toList().singleOrNull() ?: return bytes
    val ids = try {
        findChapterStarts(xhtml, anchors)
    } catch (_: XmlPullParserException) {
        return bytes
    }
    if (ids.isEmpty()) return bytes

    // Keep the fallback ahead of publisher CSS and install it during parsing. A literal style
    // element here would make Readium treat this file as styled and omit its default stylesheet.
    val css = ids.joinToString(separator = ",", postfix = "{break-before:column}") {
        ":where([id=\"$it\"])"
    }
    val script = """<script>(function(){
        const sheet = document.createElementNS('$XhtmlNamespace', 'style');
        sheet.textContent = '$css';
        document.currentScript.before(sheet);
        })();</script>""".trimIndent()
    val insertion = head.range.last + 1
    return (xhtml.substring(0, insertion) + script + xhtml.substring(insertion)).toByteArray()
}

private fun findChapterStarts(xhtml: String, anchors: Map<String, Int>): List<String> {
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(StringReader(xhtml))
    }
    val stack = mutableListOf<XhtmlElement>()
    val chapters = mutableListOf<XhtmlElement>()
    val ids = mutableSetOf<String>()
    val duplicateIds = mutableSetOf<String>()
    var sawHead = false
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                val parent = stack.lastOrNull()
                val isFirstChild = parent?.children == 0
                if (parent != null) parent.children++
                val name = parser.name.takeIf { parser.namespace == XhtmlNamespace }.orEmpty()
                sawHead = sawHead || (name == "head" && parser.prefix.isNullOrEmpty() && parent?.name == "html")
                val id = parser.getAttributeValue("", "id")
                if (id != null && !ids.add(id)) duplicateIds += id
                val type = parser.getAttributeValue(EpubNamespace, "type").orEmpty()
                val explicit = (name == "section" || name == "div") &&
                    ("chapter" in type.split(Whitespace) || parser.getAttributeValue("", "role") == "doc-chapter")
                val potentialTopLevel = name == "section" && parent?.name == "body" && type.isBlank()
                val element = XhtmlElement(name, id, parent, isFirstChild, explicit, potentialTopLevel)
                if ((explicit && stack.none { it.explicitChapter } || potentialTopLevel) &&
                    id != null && SafeId.matches(id)
                ) {
                    chapters += element
                }
                if (isFirstChild && Heading.matches(name)) {
                    val container = if (parent.name == "header" && parent.firstChild) parent.parent else parent
                    if (container != null) {
                        container.startsWithHeading = true
                        container.headingId = id
                    }
                }
                stack += element
            }
            XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
        }
    }
    if (!sawHead) return emptyList()
    return chapters.filter { chapter ->
        val id = requireNotNull(chapter.id)
        val depth = (listOfNotNull(id, chapter.headingId) - duplicateIds).minOfOrNull { anchors[it] ?: Int.MAX_VALUE }
        chapter.startsWithHeading && chapter.children >= 2 && id !in duplicateIds && depth != null && depth != Int.MAX_VALUE &&
            (chapter.explicitChapter || (chapter.potentialTopLevel && depth == 0))
    }.groupBy { it.parent }.values.flatMap { siblings -> siblings.drop(1).map { requireNotNull(it.id) } }
}

private class XhtmlElement(
    val name: String,
    val id: String?,
    val parent: XhtmlElement?,
    val firstChild: Boolean,
    val explicitChapter: Boolean,
    val potentialTopLevel: Boolean,
) {
    var children = 0
    var startsWithHeading = false
    var headingId: String? = null
}

private const val MaxAnchors = 1_000
private const val MaxXhtmlBytes = 2 * 1024 * 1024
private const val XhtmlNamespace = "http://www.w3.org/1999/xhtml"
private const val EpubNamespace = "http://www.idpf.org/2007/ops"
private val HeadStart = Regex("""<head(?:\s[^<>]*)?>""")
private val SafeId = Regex("""[a-zA-Z0-9_][a-zA-Z0-9._:-]*""")
private val Heading = Regex("h[1-6]")
private val Whitespace = Regex("\\s+")
