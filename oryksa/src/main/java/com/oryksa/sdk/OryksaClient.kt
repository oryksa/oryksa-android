package com.oryksa.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** SDK version sent in the `X-ORYKSA-SDK` header. */
const val ORYKSA_SDK_VERSION = "1.1.0"

/**
 * Error returned by the ORYKSA API. [code] is stable, for example `interaction_limit_reached`,
 * `rate_limited`, `plan_required` or `session_expired`.
 */
class OryksaException(val status: Int, val code: String, message: String) : Exception(message)

/** Public look of the AI employee (the same data the ORYKSA website chat shows). */
data class OryksaAgent(
    val name: String,
    val avatar: String,
    val business: String? = null,
    val greeting: Map<String, String> = emptyMap(),
    val subtitle: Map<String, String> = emptyMap(),
    val suggestions: Map<String, List<String>> = emptyMap(),
    val voiceReplies: Boolean = false,
    val conversationId: String? = null,
    /** ElevenLabs voice chosen for the AI in ORYKSA (the server speaks with it). */
    val voice: String? = null,
    /** Main language of the AI (`pt`, `en`, `es`...), from ORYKSA. */
    val language: String? = null,
) {
    /** The AI photo from "Your AI" in ORYKSA (never the owner's photo). Same as [avatar]. */
    val photo: String get() = avatar

    companion object {
        const val DEFAULT_AVATAR = "https://oryksa.com/assets/img/avatar_official_oryksa.png"

        /** Reads the `GET /v1/client/agent` response. */
        fun fromJson(j: JSONObject): OryksaAgent {
            fun strMap(o: JSONObject?): Map<String, String> =
                o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()
            fun listMap(o: JSONObject?): Map<String, List<String>> = o?.keys()?.asSequence()?.associateWith { k ->
                val a = o.optJSONArray(k) ?: JSONArray()
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyMap()
            return OryksaAgent(
                name = j.optString("name").ifBlank { "ORYKSA" },
                avatar = j.optString("avatar").ifBlank { DEFAULT_AVATAR },
                business = j.optString("business").takeIf { it.isNotBlank() && it != "null" },
                greeting = strMap(j.optJSONObject("greeting")),
                subtitle = strMap(j.optJSONObject("subtitle")),
                suggestions = listMap(j.optJSONObject("suggestions")),
                voiceReplies = j.optBoolean("voice_replies", false),
                conversationId = j.optString("conversation_id").takeIf { it.isNotBlank() && it != "null" },
                voice = j.optString("voice").takeIf { it.isNotBlank() && it != "null" },
                language = j.optString("language").takeIf { it.isNotBlank() && it != "null" },
            )
        }

        /** Picks the value for [lang] with fallbacks (br uses pt, then en). */
        fun <T> pick(m: Map<String, T>, lang: String): T? =
            m[lang] ?: (if (lang == "br") m["pt"] else null) ?: m["en"] ?: m.values.firstOrNull()
    }
}

/** One message of the conversation (`user` or `assistant`). */
data class OryksaMessage(val role: String, val content: String)

/**
 * Answer of [OryksaClient.send]: `replied` with the text, or `pending` while the AI is still writing.
 * With `voice = true`, [speech] is the short spoken version (1-2 sentences, no markdown); the whole
 * [reply] stays in the chat. [whisper]: the customer whispered, speak it whispered.
 */
data class OryksaReply(
    val status: String,
    val reply: String?,
    val conversationId: String?,
    val speech: String? = null,
    val whisper: Boolean = false,
)

/** Where the customer is inside your app. Sent with each message so the AI answers about the current screen. */
data class OryksaAppContext(
    /** Screen id, for example `product`, `cart`, `booking`. */
    val screen: String? = null,
    /** Title shown on the screen, for example the product name and price. */
    val title: String? = null,
    /** What is listed on the screen (products, services, times). Up to 20. */
    val items: List<String> = emptyList(),
) {
    /** JSON sent to the API. */
    fun toJson(): JSONObject = JSONObject().apply {
        screen?.let { put("screen", it) }
        title?.let { put("title", it) }
        if (items.isNotEmpty()) put("items", JSONArray(items.take(20)))
    }
}

/**
 * In-app client. Uses a short-lived session token (`oryk_cs_...`) created by YOUR server with
 * `POST /v1/sessions`. The secret API key never goes into the app. Pass [getToken] so the client can
 * ask your server for a new token when the current one expires.
 */
class OryksaClient(
    token: String? = null,
    private val getToken: (suspend () -> String)? = null,
    baseUrl: String = "https://api.oryksa.com/v1",
    private val timeoutMs: Int = 60_000,
) {
    private var token: String? = token
    private val base = baseUrl.trimEnd('/')

    init {
        require(token != null || getToken != null) { "OryksaClient needs a token or getToken." }
        require(token?.startsWith("oryk_live_") != true) { "Never use the secret API key in an app. Use a session token (oryk_cs_...)." }
    }

    private suspend fun currentToken(force: Boolean): String {
        val g = getToken
        if ((token == null || force) && g != null) token = g()
        return token?.takeIf { it.isNotBlank() } ?: throw OryksaException(401, "no_token", "No session token.")
    }

    /** One HTTP exchange: returns the raw bytes of a successful answer, throws [OryksaException] otherwise. */
    private suspend fun exchange(method: String, path: String, payload: ByteArray?, contentType: String?, force: Boolean): ByteArray {
        val tok = currentToken(force)
        return withContext(Dispatchers.IO) {
            val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("X-ORYKSA-SDK", "android/$ORYKSA_SDK_VERSION")
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/json")
                }
            }
            try {
                if (payload != null) c.outputStream.use { it.write(payload) }
                val status = c.responseCode
                val bytes = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (status >= 400) {
                    val e = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }.optJSONObject("error")
                    throw OryksaException(status, e?.optString("code")?.ifBlank { null } ?: "http_$status",
                        e?.optString("message")?.ifBlank { null } ?: "Request failed with HTTP $status")
                }
                bytes
            } catch (e: IOException) {
                throw OryksaException(0, "network_error", "Could not reach ORYKSA: ${e.message}")
            } finally {
                c.disconnect()
            }
        }
    }

    private suspend fun exchangeRetry(method: String, path: String, payload: ByteArray?, contentType: String?): ByteArray = try {
        exchange(method, path, payload, contentType, false)
    } catch (e: OryksaException) {
        if ((e.code == "session_expired" || e.status == 401) && getToken != null) exchange(method, path, payload, contentType, true) else throw e
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val bytes = exchangeRetry(method, path, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json")
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }
    }

    /** Name, photo, greeting and suggestions of the AI employee. */
    suspend fun agent(): OryksaAgent = OryksaAgent.fromJson(request("GET", "/client/agent"))

    /**
     * Sends a message. The status is `pending` when the AI needs a few more seconds: use [sendAndWait].
     * [appContext]: the screen the customer is on inside your app. [voice]: the reply will be heard (it also
     * carries a short `speech`). [whisper]: the customer whispered. [voiceStats]: audio numbers of the voice turn.
     */
    suspend fun send(
        message: String,
        appContext: OryksaAppContext? = null,
        voice: Boolean = false,
        whisper: Boolean = false,
        voiceStats: JSONObject? = null,
    ): OryksaReply {
        val body = JSONObject().put("message", message)
        appContext?.let { body.put("app_context", it.toJson()) }
        if (voice) body.put("voice", true)
        if (whisper) body.put("whisper", true)
        if (voiceStats != null) body.put("platform", "android").put("voice_stats", voiceStats)
        val j = request("POST", "/client/chat", body)
        return OryksaReply(j.optString("status", "replied"), j.optString("reply").takeIf { it.isNotBlank() && it != "null" },
            j.optString("conversation_id").takeIf { it.isNotBlank() },
            j.optString("speech").takeIf { it.isNotBlank() && it != "null" }, j.optBoolean("whisper", false))
    }

    /** Like [sendAndWait], but returns the whole [OryksaReply] (with `speech` when [voice] is on). */
    suspend fun sendAndWaitReply(
        message: String,
        maxWaitMs: Long = 40_000,
        appContext: OryksaAppContext? = null,
        voice: Boolean = false,
        whisper: Boolean = false,
        voiceStats: JSONObject? = null,
    ): OryksaReply {
        val r = send(message, appContext, voice, whisper, voiceStats)
        if (r.status != "pending") return r
        val end = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < end) {
            delay(1_500)
            val last = messages().lastOrNull()
            if (last?.role == "assistant") return OryksaReply("replied", last.content, r.conversationId, whisper = whisper)
        }
        return r
    }

    /**
     * The AI's voice (ElevenLabs, the voice chosen in ORYKSA) for one reply of this conversation, as MP3.
     * Returns null when the voice is not available: then show the text only (never a robot voice).
     */
    suspend fun tts(text: String, whisper: Boolean = false): ByteArray? = runCatching {
        val body = JSONObject().put("text", text)
        if (whisper) body.put("whisper", true)
        exchangeRetry("POST", "/client/tts", body.toString().toByteArray(Charsets.UTF_8), "application/json")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Turns the customer's voice into text. [wav] is 16 kHz mono PCM16 WAV, up to 15 seconds.
     * Returns "" when nothing was said and null when it failed.
     */
    suspend fun transcribe(wav: ByteArray): String? = runCatching {
        val boundary = "oryksa-" + System.nanoTime()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = head.toByteArray(Charsets.UTF_8) + wav + tail.toByteArray(Charsets.UTF_8)
        val bytes = exchangeRetry("POST", "/client/transcribe", body, "multipart/form-data; boundary=$boundary")
        JSONObject(String(bytes, Charsets.UTF_8)).optString("text").trim()
    }.getOrNull()

    /** Reports a voice turn that produced no message (nothing heard, a cut with nothing said). */
    suspend fun voiceStats(stats: JSONObject) {
        runCatching { request("POST", "/client/voice-stats", JSONObject().put("platform", "android").put("voice_stats", stats)) }
    }

    /** Messages of this conversation. */
    suspend fun messages(): List<OryksaMessage> {
        val a = request("GET", "/client/messages").optJSONArray("messages") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { OryksaMessage(it.optString("role", "assistant"), it.optString("content")) }
        }
    }

    /** Sends a message and waits for the reply text. */
    suspend fun sendAndWait(message: String, maxWaitMs: Long = 40_000, appContext: OryksaAppContext? = null): String? =
        sendAndWaitReply(message, maxWaitMs, appContext).reply
}
