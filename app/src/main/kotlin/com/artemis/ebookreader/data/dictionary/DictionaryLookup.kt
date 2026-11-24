package com.artemis.ebookreader.data.dictionary

import java.text.Normalizer
import java.util.Locale

internal data class DictionaryQuery(val selection: String, val key: String)

internal fun dictionaryQuery(selection: String): DictionaryQuery? {
    if (selection.length > 256) return null
    val normalized = Normalizer.normalize(selection, Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\p{Z}]+"), " ")
        .trimStart { !it.isLetterOrDigit() }
    val display = normalized.trimEnd { character ->
        when (character.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> false
            else -> !character.isLetterOrDigit()
        }
    }.let { word ->
        val trailing = normalized.substring(word.length)
        if (trailing.startsWith('.') && word.matches(Regex("(?:[A-Za-z]\\.)+[A-Za-z]"))) "$word." else word
    }
    if (display.length !in 1..80 || display.count { it == ' ' } > 7 || display.none { it.isLetter() }) {
        return null
    }
    val key = display.lowercase(Locale.ROOT)
        .replace(Regex("[‘’ʼ]"), "'")
        .replace(Regex("[‐‑‒–—−]"), "-")
    if (!key.matches(Regex("[\\p{L}\\p{M}\\p{N} .'\\-]+"))) return null
    return DictionaryQuery(display, key)
}

internal enum class WordNetPart(val label: String) {
    Noun("Noun"), Verb("Verb"), Adjective("Adjective"), Adverb("Adverb"),
}

internal data class DictionarySense(val definition: String, val examples: List<String>)
internal data class DictionaryMeaning(val partOfSpeech: String?, val senses: List<DictionarySense>)
internal data class DictionaryCredit(val name: String, val url: String)
internal data class DictionaryEntry(
    val word: String,
    val meanings: List<DictionaryMeaning>,
    val phonetics: List<String> = emptyList(),
    val credits: List<DictionaryCredit> = emptyList(),
)

internal sealed interface DictionaryResult {
    data class Found(val entries: List<DictionaryEntry>) : DictionaryResult
    data object Missing : DictionaryResult
    data object Unavailable : DictionaryResult
    data object RateLimited : DictionaryResult
}

internal data class WordNetForm(val word: String, val part: WordNetPart)

// WordNet's documented Morphy rules. Search the index for the specified part of speech.
private val nounEndings = listOf(
    "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh",
    "men" to "man", "ies" to "y", "s" to "",
)
private val verbEndings = listOf(
    "ies" to "y", "es" to "e", "es" to "", "s" to "",
    "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
)
private val adjectiveEndings = listOf("er" to "", "est" to "", "er" to "e", "est" to "e")

internal fun wordNetInflections(word: String): List<WordNetForm> {
    // Require an exact match for phrases. Treat recorded multiword exceptions separately.
    if (' ' in word || '.' in word) return emptyList()
    val possessiveBase = word.removeSuffix("'s")
    if (possessiveBase != word) return listOf(WordNetForm(possessiveBase, WordNetPart.Noun))
    return buildList {
        fun addForms(part: WordNetPart, endings: List<Pair<String, String>>) {
            for ((suffix, replacement) in endings) {
                if (!word.endsWith(suffix)) continue
                val stem = word.dropLast(suffix.length)
                if (stem.length < 2) continue
                add(WordNetForm(stem + replacement, part))
            }
        }
        addForms(WordNetPart.Noun, nounEndings)
        addForms(WordNetPart.Verb, verbEndings)
        addForms(WordNetPart.Adjective, adjectiveEndings)
    }.distinct()
}

internal fun lookupWordNet(
    word: String,
    exceptions: () -> List<WordNetForm>,
    entry: (String, Set<WordNetPart>?) -> DictionaryEntry?,
): List<DictionaryEntry> {
    val exact = entry(word, null)
    fun resolve(forms: List<WordNetForm>): List<DictionaryEntry> =
        forms.groupBy(WordNetForm::word, WordNetForm::part).mapNotNull { (base, parts) ->
            entry(base, parts.toSet())
        }
    val recorded = exceptions().filter { form ->
        form.word != word && exact?.meanings.orEmpty().none { it.partOfSpeech == form.part.label }
    }
    val baseForms = resolve(recorded)
    if (exact != null) return listOf(exact) + baseForms
    return baseForms.ifEmpty { resolve(wordNetInflections(word)) }
}
