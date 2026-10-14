package com.taboia872.androidassist.search

import android.util.Log
import com.taboia872.androidassist.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.URLDecoder
import java.net.URLEncoder

data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * Keyless DuckDuckGo search client.
 *
 * Uses the no-JS HTML endpoint (html.duckduckgo.com/html/), which serves a
 * complete results page in a single POST with no API key, no vqd token and
 * no JavaScript. Free and privacy-friendly.
 *
 * Generic UA from Net (verified empirically: DDG's bot filter accepts it).
 * Result links may be wrapped in redirect URLs of the form
 *   //duckduckgo.com/l/?uddg=<url-encoded real url>&rut=...
 * and are decoded here. Rate limiting (HTTP 202 anomaly page) surfaces as
 * an empty result list rather than an exception.
 */
object DuckDuckGo {

    private const val TAG = "DuckDuckGoSearch"
    private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_RESULTS = 5

    private val RESULT_LINK = Regex(
        """<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val RESULT_SNIPPET = Regex(
        """<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val TAG_REGEX = Regex("""<[^>]+>""")

    /** Decode DuckDuckGo's wrapped redirect href into the real target URL. */
    fun unwrapUrl(href: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
            ?: return href
        return try {
            URLDecoder.decode(uddg, "UTF-8")
        } catch (e: Exception) {
            uddg
        }
    }

    private fun stripTags(html: String): String {
        var out = TAG_REGEX.replace(html, "")
        out = out.replace("&nbsp;", " ")
        out = out.replace(Regex("&#x27;|&#39;"), "'")
        out = out.replace("&" + "quot;", '"'.toString())
        out = out.replace("&" + "lt;", "<")
        out = out.replace("&" + "gt;", ">")
        out = out.replace("&" + "amp;", "&")
        return out.trim()
    }

    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                searchDdg(query, maxResults)
            } catch (e: Exception) {
                Log.e(TAG, "DuckDuckGo search failed", e)
                emptyList()
            }
        }

    private fun searchDdg(query: String, maxResults: Int): List<SearchResult> {
        val connection = Net.open(DDG_HTML_URL).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        try {
            val form = "q=" + URLEncoder.encode(query, "UTF-8")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(form)
            }

            if (connection.responseCode != 200) {
                // 202 = DDG's soft rate-limit/anomaly page
                Log.w(TAG, "DuckDuckGo HTML error: HTTP ${connection.responseCode}")
                return emptyList()
            }

            val body = connection.inputStream.bufferedReader().readText()
            return parseResults(body, maxResults)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        for (link in RESULT_LINK.findAll(html)) {
            if (results.size >= maxResults) break
            val href = link.groupValues[1]
            val title = stripTags(link.groupValues[2])
            val url = unwrapUrl(href)
            if (title.isBlank() || !url.startsWith("http")) continue

            val after = html.substring(link.range.last + 1)
            val snippet = RESULT_SNIPPET.find(after)?.let { stripTags(it.groupValues[1]) }
                ?: ""

            results.add(SearchResult(title = title, url = url, snippet = snippet))
        }
        return results
    }
}
