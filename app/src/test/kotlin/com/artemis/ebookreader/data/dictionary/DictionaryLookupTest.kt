package com.artemis.ebookreader.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryLookupTest {
    @Test
    fun normalizesCaseUnicodeWhitespaceAndQuotes() {
        assertEquals("credit card", dictionaryQuery("  “ＣＲＥＤＩＴ\u00a0CARD!”  ")?.key)
        assertEquals("o'clock", dictionaryQuery("“O’CLOCK”")?.key)
        assertEquals("mother-in-law", dictionaryQuery("(Mother‑in‑Law).")?.key)
        assertEquals("café", dictionaryQuery("Cafe\u0301.")?.key)
        assertEquals("a.m.", dictionaryQuery("(A.M.)")?.key)
    }

    @Test
    fun rejectsBlankLongAndNonLexicalSelections() {
        assertNull(dictionaryQuery("…!?"))
        assertNull(dictionaryQuery("a".repeat(81)))
        assertNull(dictionaryQuery("This is a selection of more than eight words"))
        assertNull(dictionaryQuery("one/two"))
    }

    @Test
    fun prioritizesExactPhrasesAndDoesNotStemUnmatchedPhrases() {
        val phrase = entry("credit card", WordNetPart.Noun)
        assertEquals(listOf(phrase), lookupWordNet(phrase.word, ::emptyList) { word, _ ->
            phrase.takeIf { it.word == word }
        })
        assertTrue(wordNetInflections("credit cards").isEmpty())
    }

    @Test
    fun recordedInflectionsCanSupplementExactSensesInAnotherPartOfSpeech() {
        val noun = entry("running", WordNetPart.Noun)
        val verb = entry("run", WordNetPart.Verb)
        val forms = listOf(WordNetForm("run", WordNetPart.Verb))
        val result = lookupWordNet(noun.word, { forms }) { word, parts ->
            when {
                word == noun.word && parts == null -> noun
                word == verb.word && parts == setOf(WordNetPart.Verb) -> verb
                else -> null
            }
        }
        assertEquals(listOf(noun, verb), result)
    }

    @Test
    fun limitsDetachmentToDocumentedPartsOfSpeechAndDoesNotReturnUnknownLemmas() {
        assertTrue(wordNetInflections("quasars").contains(WordNetForm("quasar", WordNetPart.Noun)))
        assertTrue(wordNetInflections("quasars").none { it.part == WordNetPart.Adverb })
        val searched = mutableListOf<Set<WordNetPart>?>()
        val result = lookupWordNet("blarged", ::emptyList) { _, parts ->
            searched.add(parts)
            null
        }
        assertTrue(result.isEmpty())
        assertFalse(searched.isEmpty())
        assertEquals(listOf(null, setOf(WordNetPart.Verb), setOf(WordNetPart.Verb)), searched)
    }

    @Test
    fun separatesMultipleInlineExamplesBeforeTheTrailingSemicolon() {
        val sense = parseWordNetGloss(
            """the work of applying something; "the doctor prescribed a topical application of iodine"; """ +
                """"a complete bleach requires several applications"; "the surface was ready for a coating of paint";""",
        )
        assertEquals("the work of applying something", sense.definition)
        assertEquals(
            listOf(
                "the doctor prescribed a topical application of iodine",
                "a complete bleach requires several applications",
                "the surface was ready for a coating of paint",
            ),
            sense.examples,
        )
    }

    @Test
    fun keepsAttributedAndNewlineExamplesInOrder() {
        val sense = parseWordNetGloss(
            """
            an open clash between two opposing groups (or individuals); "the harder the conflict the more glorious the triumph"--Thomas Paine
            police tried to control the battle between the pro- and anti-abortion mobs
            """.trimIndent(),
        )
        assertEquals("an open clash between two opposing groups (or individuals)", sense.definition)
        assertEquals(
            listOf(
                "\"the harder the conflict the more glorious the triumph\"--Thomas Paine",
                "police tried to control the battle between the pro- and anti-abortion mobs",
            ),
            sense.examples,
        )
    }

    @Test
    fun preservesSemicolonsAndQuotedUsageProse() {
        val definition = """of the distant or comparatively distant past; "lang syne" is Scottish"""
        val sense = parseWordNetGloss(
            """of the distant or comparatively distant past; "We met once long ago"; "lang syne" is Scottish""",
        )
        assertEquals(definition, sense.definition)
        assertEquals(listOf("We met once long ago"), sense.examples)
        assertEquals(DictionarySense(definition, emptyList()), parseWordNetGloss(definition))
    }

    @Test
    fun readsNewlineExamplesWithoutChangingQuotedDefinitionText() {
        val definition = """significant progress (especially in the phrase "make strides")"""
        val example = "they made big strides in productivity"
        assertEquals(DictionarySense(definition, listOf(example)), parseWordNetGloss("$definition\n$example"))
    }

    @Test
    fun mapsOnlineStatusFailures() {
        assertEquals(DictionaryResult.Missing, onlineDictionaryFailure(404))
        assertEquals(DictionaryResult.RateLimited, onlineDictionaryFailure(429))
        for (status in listOf(301, 403, 500, 503)) {
            assertEquals(DictionaryResult.Unavailable, onlineDictionaryFailure(status))
        }
    }

    private fun entry(word: String, part: WordNetPart) =
        DictionaryEntry(word, listOf(DictionaryMeaning(part.label, listOf(DictionarySense("Test", emptyList())))))
}
