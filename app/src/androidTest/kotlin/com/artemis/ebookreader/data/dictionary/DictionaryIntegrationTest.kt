package com.artemis.ebookreader.data.dictionary

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryIntegrationTest {
    @Test
    fun bundledDatabaseResolvesExactPhrasesInflectionsAndMissingWords() = runBlocking {
        val dictionary = OfflineDictionary(InstrumentationRegistry.getInstrumentation().targetContext)
        suspend fun lookup(word: String) = dictionary.lookup(checkNotNull(dictionaryQuery(word)))
        val phrase = lookup("“CREDIT CARD”") as DictionaryResult.Found
        assertEquals("credit card", phrase.entries.single().word)
        val inflected = lookup("children") as DictionaryResult.Found
        assertEquals("child", inflected.entries.single().word)
        val running = lookup("running") as DictionaryResult.Found
        assertTrue(running.entries.any { it.word == "run" && it.meanings.single().partOfSpeech == "Verb" })
        assertEquals(DictionaryResult.Missing, lookup("zzqxvv"))
    }

    @Test
    fun parsesAttributedDefinitionsAndRejectsMisformedOrUnattributedResponses() {
        val response = """
            [{
              "word":"hello",
              "phonetics":[{"text":"/həˈləʊ/"}],
              "sourceUrls":["https://en.wiktionary.org/wiki/hello"],
              "license":{"name":"CC BY-SA 3.0","url":"https://creativecommons.org/licenses/by-sa/3.0"},
              "meanings":[{"partOfSpeech":"interjection","definitions":[{"definition":"A greeting.","example":"Hello!"}]}]
            }]
        """.trimIndent()
        val entry = (parseOnlineDictionary(response) as DictionaryResult.Found).entries.single()
        assertEquals(listOf("/həˈləʊ/"), entry.phonetics)
        assertEquals(listOf("Wiktionary", "CC BY-SA 3.0"), entry.credits.map { it.name })
        assertEquals(listOf("Hello!"), entry.meanings.single().senses.single().examples)
        assertEquals(DictionaryResult.Unavailable, parseOnlineDictionary("invalid JSON"))
        assertEquals(
            DictionaryResult.Unavailable,
            parseOnlineDictionary(response.replace("https://en.wiktionary.org", "https://example.test")),
        )
    }
}
