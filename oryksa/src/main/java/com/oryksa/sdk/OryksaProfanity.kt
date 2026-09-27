package com.oryksa.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Swear words the customer types show as asterisks (owner rule: every ORYKSA chat). One list for all
 * ORYKSA clients: GET https://app.oryksa.com/widget/profanity.json?lang= (kept 24 hours). The AI's
 * replies already come filtered from the server.
 */
object OryksaProfanity {
    private const val URL_BASE = "https://app.oryksa.com/widget/profanity.json"
    private const val DAY_MS = 24L * 3600 * 1000
    private val patterns = HashMap<String, Pattern>()
    private val loadedAt = HashMap<String, Long>()

    /** Language key of the list: pt, br, en or es. */
    fun key(lang: String): String = if (lang.contains("br")) "br" else lang.take(2).ifEmpty { "en" }

    /** Uses a pattern directly (tests, or your own copy of the list). */
    @Synchronized
    fun use(lang: String, pattern: String, flags: String = "giu") {
        val k = key(lang)
        var f = Pattern.UNICODE_CHARACTER_CLASS
        if (flags.contains('i')) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        runCatching { Pattern.compile(pattern, f) }.getOrNull()?.let { patterns[k] = it; loadedAt[k] = System.currentTimeMillis() }
    }

    /** Loads (or refreshes after 24 h) the list for [lang]. Never throws. */
    suspend fun load(lang: String) {
        val k = key(lang)
        val at = synchronized(this) { loadedAt[k] }
        if (at != null && System.currentTimeMillis() - at < DAY_MS) return
        withContext(Dispatchers.IO) {
            runCatching {
                val c = (URL("$URL_BASE?lang=$k").openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000 }
                try {
                    if (c.responseCode == 200) {
                        val d = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        if (d.has("pattern")) use(k, d.getString("pattern"), d.optString("flags", "giu"))
                    }
                } finally {
                    c.disconnect()
                }
            }
        }
    }

    /** [text] with the swear words replaced by asterisks (at least 3). Unchanged when the list is not loaded. */
    fun mask(text: String, lang: String): String {
        val p = synchronized(this) { patterns[key(lang)] } ?: return text
        if (text.isEmpty()) return text
        val m = p.matcher(text)
        val out = StringBuilder()
        var last = 0
        while (m.find()) {
            val pre = m.group(1) ?: ""
            val word = m.group().substring(pre.length).replace(Regex("\\s"), "")
            out.append(text, last, m.start()).append(pre).append("*".repeat(maxOf(3, word.length)))
            last = m.end()
        }
        out.append(text, last, text.length)
        return out.toString()
    }
}
