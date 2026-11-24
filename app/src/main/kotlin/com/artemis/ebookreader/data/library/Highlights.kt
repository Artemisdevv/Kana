package com.artemis.ebookreader.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

internal enum class HighlightColor(val key: String, val argb: Int) {
    Yellow("yellow", 0xFFE4C65D.toInt()), Green("green", 0xFF83B88A.toInt()), Blue("blue", 0xFF83A8DF.toInt()),
    Pink("pink", 0xFFD68DA7.toInt()), Purple("purple", 0xFFB49AD9.toInt());

    companion object {
        fun fromKey(key: String): HighlightColor = entries.firstOrNull { it.key == key } ?: Yellow
    }
}

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(Book::class, ["bookId"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["bookId", "rangeKey"], unique = true),
        Index(value = ["bookId", "spineIndex", "resourceProgression", "createdAt", "highlightId"]),
    ],
)
internal data class Highlight(
    @PrimaryKey val highlightId: String,
    val bookId: String,
    val rangeKey: String,
    val locator: String,
    val selectedText: String,
    val chapter: String?,
    val colorKey: String,
    val note: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    val spineIndex: Int,
    val resourceProgression: Double?,
)

internal data class SavedHighlight(val record: Highlight, val locator: Locator) {
    val color: HighlightColor get() = HighlightColor.fromKey(record.colorKey)
}

internal const val MaxHighlightLength = 50_000
internal const val MaxNoteLength = 24_000

// The current page's selectors and progress are not part of the selected quote's identity.
internal fun Locator.highlightAnchor(): Locator = copy(
    href = href.removeFragment().normalize(),
    locations = Locator.Locations(progression = locations.progression, totalProgression = locations.totalProgression),
)

internal fun Locator.highlightRangeKey(): String =
    highlightRangeKey(href.removeFragment().normalize().toString(), text.before, text.highlight, text.after)

internal fun highlightRangeKey(resource: String, before: String?, selected: String?, after: String?): String {
    val quote = buildString {
        for (part in listOf(resource, before.orEmpty(), selected.orEmpty(), after.orEmpty())) {
            append(part.length).append(':').append(part)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(quote.encodeToByteArray()).toHexString()
}

internal fun Highlight.resolve(): SavedHighlight? = runCatching {
    Locator.fromJSON(JSONObject(locator))?.takeIf { !it.text.highlight.isNullOrBlank() }
        ?.let { SavedHighlight(this, it) }
}.getOrNull()
