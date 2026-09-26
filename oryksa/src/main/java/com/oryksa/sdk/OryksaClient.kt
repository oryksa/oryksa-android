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
const val ORYKSA_SDK_VERSION = "1.0.0"

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
) {
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
            )
        }

        /** Picks the value for [lang] with fallbacks (br uses pt, then en). */
        fun <T> pick(m: Map<String, T>, lang: String): T? =
            m[lang] ?: (if (lang == "br") m["pt"] else null) ?: m["en"] ?: m.values.firstOrNull()
    }
}

/** One message of the conversation (`user` or `assistant`). */
data class OryksaMessage(val role: String, val content: String)

/** Answer of [OryksaClient.send]: `replied` with the text, or `pending` while the AI is still writing. */
data class OryksaReply(val status: String, val reply: String?, val conversationId: String?)

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

    private suspend fun raw(method: String, path: String, body: JSONObject?, force: Boolean): JSONObject {
        val tok = currentToken(force)
        return withContext(Dispatchers.IO) {
            val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("X-ORYKSA-SDK", "android/$ORYKSA_SDK_VERSION")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = c.responseCode
                val text = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (status >= 400) {
                    val e = json.optJSONObject("error")
                    throw OryksaException(status, e?.optString("code")?.ifBlank { null } ?: "http_$status",
                        e?.optString("message")?.ifBlank { null } ?: "Request failed with HTTP $status")
                }
                json
            } catch (e: IOException) {
                throw OryksaException(0, "network_error", "Could not reach ORYKSA: ${e.message}")
            } finally {
                c.disconnect()
            }
        }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = try {
        raw(method, path, body, false)
    } catch (e: OryksaException) {
        if ((e.code == "session_expired" || e.status == 401) && getToken != null) raw(method, path, body, true) else throw e
    }

    /** Name, photo, greeting and suggestions of the AI employee. */
    suspend fun agent(): OryksaAgent = OryksaAgent.fromJson(request("GET", "/client/agent"))

    /** Sends a message. The status is `pending` when the AI needs a few more seconds: use [sendAndWait]. */
    suspend fun send(message: String): OryksaReply {
        val j = request("POST", "/client/chat", JSONObject().put("message", message))
        return OryksaReply(j.optString("status", "replied"), j.optString("reply").takeIf { it.isNotBlank() && it != "null" },
            j.optString("conversation_id").takeIf { it.isNotBlank() })
    }

    /** Messages of this conversation. */
    suspend fun messages(): List<OryksaMessage> {
        val a = request("GET", "/client/messages").optJSONArray("messages") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { OryksaMessage(it.optString("role", "assistant"), it.optString("content")) }
        }
    }

    /** Sends a message and waits for the reply text. */
    suspend fun sendAndWait(message: String, maxWaitMs: Long = 40_000): String? {
        val r = send(message)
        if (r.status != "pending") return r.reply
        val end = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < end) {
            delay(1_500)
            val last = messages().lastOrNull()
            if (last?.role == "assistant") return last.content
        }
        return null
    }
}
