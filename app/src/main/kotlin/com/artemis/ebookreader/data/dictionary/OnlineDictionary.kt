package com.artemis.ebookreader.data.dictionary

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun onlineDictionaryFailure(status: Int): DictionaryResult = when (status) {
    404 -> DictionaryResult.Missing
    429 -> DictionaryResult.RateLimited
    else -> DictionaryResult.Unavailable
}

internal object OnlineDictionary {
    suspend fun lookup(query: DictionaryQuery): DictionaryResult = withTimeoutOrNull(12_000) {
        withContext(Dispatchers.IO) { request(query) }
    } ?: DictionaryResult.Unavailable

    private suspend fun request(query: DictionaryQuery): DictionaryResult =
        suspendCancellableCoroutine { continuation ->
            val term = URLEncoder.encode(query.key, "UTF-8").replace("+", "%20")
            val connection = try {
                URI.create("https://api.dictionaryapi.dev/api/v2/entries/en/$term")
                    .toURL().openConnection() as HttpsURLConnection
            } catch (_: IOException) {
                continuation.resume(DictionaryResult.Unavailable)
                return@suspendCancellableCoroutine
            }
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Kana-Dictionary")
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val result = try {
                val status = connection.responseCode
                if (status != 200) onlineDictionaryFailure(status)
                else connection.inputStream.use { parseOnlineDictionary(it.readDictionaryResponse()) }
            } catch (_: IOException) {
                DictionaryResult.Unavailable
            } finally {
                connection.disconnect()
            }
            continuation.resume(result)
        }
}

private fun InputStream.readDictionaryResponse(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        if (output.size() + count > 384 * 1024) throw IOException("Dictionary response too large")
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}

internal fun parseOnlineDictionary(body: String): DictionaryResult = try {
    val response = JSONArray(body)
    val entries = response.objects().mapNotNull entry@{ item ->
        val word = item.text("word") ?: return@entry null
        val meanings = item.optJSONArray("meanings").objects().mapNotNull { meaning ->
            val senses = meaning.optJSONArray("definitions").objects().mapNotNull definition@{ sense ->
                val definition = sense.text("definition") ?: return@definition null
                DictionarySense(definition, listOfNotNull(sense.text("example")))
            }
            if (senses.isEmpty()) null else DictionaryMeaning(meaning.text("partOfSpeech"), senses)
        }
        if (meanings.isEmpty()) return@entry null
        val phonetics = (listOfNotNull(item.text("phonetic")) +
            item.optJSONArray("phonetics").objects().mapNotNull { it.text("text") }).distinct()
        val sourceUrls = item.optJSONArray("sourceUrls")
        val sources = List(sourceUrls?.length() ?: 0) { sourceUrls?.opt(it) as? String }
            .mapNotNull { url -> url?.let { credit(it, "en.wiktionary.org", "Wiktionary") } }.distinct()
        val license = item.optJSONObject("license")?.let {
            val url = it.text("url") ?: return@let null
            credit(url, "creativecommons.org", it.text("name") ?: "Creative Commons")
        }
        // The provider identifies the dictionary entry source and its reuse terms.
        if (sources.isEmpty() || license == null) return@entry null
        DictionaryEntry(word, meanings, phonetics, sources + license)
    }
    if (entries.isEmpty()) DictionaryResult.Unavailable else DictionaryResult.Found(entries)
} catch (_: JSONException) {
    DictionaryResult.Unavailable
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun JSONObject.text(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

private fun credit(url: String, host: String, name: String): DictionaryCredit? {
    val uri = runCatching { URI(url) }.getOrNull()
    return if (uri?.scheme == "https" && uri.host == host && uri.rawUserInfo == null) {
        DictionaryCredit(name, url)
    } else null
}
