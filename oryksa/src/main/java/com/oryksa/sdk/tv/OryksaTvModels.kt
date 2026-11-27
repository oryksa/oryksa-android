package com.oryksa.sdk.tv

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The TV the app runs on, sent as `platform` to ORYKSA (so the owner's reports say "in your app (Fire TV)").
 * On Android it is found by [detect]; pass it to `OryksaClient(platform = ...)`.
 */
enum class OryksaTvPlatform(val wire: String) {
    ANDROID_TV("android_tv"),
    FIRE_TV("fire_tv"),
    VEGA("vega"),
    TIZEN("tizen"),
    WEBOS("webos"),
    VIDAA("vidaa"),
    TVOS("tvos"),
    ROKU("roku");

    companion object {
        /**
         * The TV this device is, from the features it declares: `amazon.hardware.fire_tv` (Fire TV), otherwise
         * leanback or `android.hardware.type.television` (Android TV / Google TV). Null on a phone or tablet.
         */
        fun detect(context: Context): OryksaTvPlatform? {
            val pm = context.packageManager
            return when {
                pm.hasSystemFeature("amazon.hardware.fire_tv") -> FIRE_TV
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature("android.hardware.type.television") -> ANDROID_TV
                else -> null
            }
        }
    }
}

/** BCP-47 tag of a [Locale] with a hyphen: `pt-BR`, `es-419`, `en`. */
fun oryksaTvLocaleTag(l: Locale): String = oryksaTvNormalizeLocale(l.toLanguageTag())

/** Normalises a locale string (`pt_BR`, `pt-br`, `PT-BR`) to `pt-BR`. Empty stays empty. */
fun oryksaTvNormalizeLocale(raw: String): String {
    val p = raw.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
    if (p.isEmpty()) return ""
    val out = mutableListOf(p.first().lowercase())
    for (s in p.drop(1)) out.add(if (s.length == 4) s.substring(0, 1).uppercase() + s.substring(1).lowercase() else s.uppercase())
    return out.joinToString("-")
}

/**
 * The language code the chat endpoint takes (`en`, `pt`, `br`, `es`, `fr`, `de`, `it`): `pt-BR` is `br`, any
 * other Portuguese is `pt`, the rest is the language code.
 */
fun oryksaTvChatLang(locale: String): String {
    val l = oryksaTvNormalizeLocale(locale)
    if (l.isEmpty()) return ""
    val lang = l.substringBefore('-')
    return if (lang == "pt") (if (l == "pt-BR") "br" else "pt") else lang
}

/** What a button of the assistant panel does. */
enum class OryksaTvActionKind { MESSAGE, PHONE_VOICE, CLOSE }

/** A button of the assistant panel, from the ORYKSA server (`tv.actions`). */
data class OryksaTvAction(val id: String, val kind: OryksaTvActionKind, val label: String, val message: String? = null) {
    companion object {
        /** Reads one entry of `tv.actions`. Null when the kind is unknown or the label is empty. */
        fun fromJson(j: JSONObject): OryksaTvAction? {
            val label = j.optString("label").trim()
            if (label.isEmpty()) return null
            val kind = when (j.optString("kind")) {
                "message" -> OryksaTvActionKind.MESSAGE
                "phone_voice" -> OryksaTvActionKind.PHONE_VOICE
                "close" -> OryksaTvActionKind.CLOSE
                else -> return null
            }
            val message = j.optString("message").trim().takeIf { it.isNotEmpty() && it != "null" }
            if (kind == OryksaTvActionKind.MESSAGE && message == null) return null
            return OryksaTvAction(j.optString("id").ifBlank { label }, kind, label, message)
        }
    }
}

/** Rules of the TV pieces, from the ORYKSA server (`tv.rules`). */
data class OryksaTvRules(
    /** The phone can be the microphone (QR). */
    val phoneVoice: Boolean = true,
    /** `auto`: use the TV microphone when the device has one. `off`: never. */
    val tvMic: String = "auto",
    /** The assistant panel reads its replies with the presenter's voice. */
    val speakReplies: Boolean = true,
    /** Longest text the server speaks in one go: longer texts are split. */
    val presenterMaxChars: Int = 600,
) {
    companion object {
        fun fromJson(j: JSONObject?): OryksaTvRules {
            if (j == null) return OryksaTvRules()
            val max = if (j.has("presenter_max_chars")) j.optInt("presenter_max_chars", 600) else 600
            return OryksaTvRules(
                phoneVoice = j.optBoolean("phone_voice", true),
                tvMic = if (j.optString("tv_mic") == "off") "off" else "auto",
                speakReplies = j.optBoolean("speak_replies", true),
                presenterMaxChars = if (max >= 40) max else 600,
            )
        }
    }
}

/**
 * The texts of the TV pieces, from the ORYKSA server (`tv.texts`), already in the locale the server chose.
 * A missing key uses a short English text and is logged once.
 */
class OryksaTvTexts(val values: Map<String, String> = emptyMap()) {
    /** The text for [key], with `{name}` placeholders replaced by [vars]. */
    fun get(key: String, vars: Map<String, String> = emptyMap()): String {
        var s = values[key]?.takeIf { it.isNotBlank() }
        if (s == null) {
            if (warned.add(key)) Log.i(TAG, "the server sent no text for \"$key\"; using the English fallback.")
            s = FALLBACK[key] ?: key
        }
        for ((k, v) in vars) s = s!!.replace("{$k}", v)
        return s!!
    }

    companion object {
        private const val TAG = "OryksaTv"
        private val warned = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** Short English texts used only when the server does not send a key (older servers). */
        val FALLBACK: Map<String, String> = mapOf(
            "assistant_title" to "Ask the AI",
            "assistant_hint" to "Pick a question or talk on your phone",
            "you" to "You",
            "thinking" to "Thinking…",
            "pair_title" to "Talk on your phone",
            "pair_step_scan" to "Point your phone camera at the code",
            "pair_step_code" to "Or open {url} and type {code}",
            "pair_waiting" to "Waiting for the phone…",
            "pair_connected" to "Phone connected",
            "pair_expired" to "The code expired",
            "listening" to "Listening…",
            "phone_left" to "The phone disconnected",
            "open_on_phone" to "Open on your phone",
            "retry" to "Try again",
            "close" to "Close",
            "error" to "Something went wrong. Try again.",
            "type_message" to "Type a message",
            "send" to "Send",
        )
    }
}

/** Everything the TV pieces read from the server: `GET /v1/client/agent?locale=...`, block `tv`. */
data class OryksaTvConfig(
    /** The locale the server chose (one of the supported ones), for example `pt-BR`. */
    val locale: String,
    /** Name of the presenter (the AI). */
    val presenterName: String = "ORYKSA",
    /** Photo of the presenter. */
    val presenterAvatar: String = DEFAULT_AVATAR,
    /** Changes when the business changes the voice of this locale (keys the voice cache). */
    val voiceRev: String? = null,
    val texts: OryksaTvTexts = OryksaTvTexts(),
    val suggestions: List<String> = emptyList(),
    val actions: List<OryksaTvAction> = emptyList(),
    val rules: OryksaTvRules = OryksaTvRules(),
    /** False when the server sent no `tv` block (older servers). */
    val fromServer: Boolean = true,
) {
    companion object {
        const val DEFAULT_AVATAR = "https://oryksa.com/assets/img/avatar_official_oryksa.png"

        /** Reads the answer of `GET /v1/client/agent?locale=...`. */
        fun fromAgentJson(j: JSONObject, requestedLocale: String): OryksaTvConfig {
            fun text(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val tv = j.optJSONObject("tv")
            if (tv == null) Log.i("OryksaTv", "the server sent no \"tv\" block in /client/agent; using the English fallback.")
            val t = tv ?: JSONObject()
            val presenter = t.optJSONObject("presenter") ?: JSONObject()
            val textsJ = t.optJSONObject("texts") ?: JSONObject()
            val texts = textsJ.keys().asSequence().associateWith { textsJ.optString(it) }
            val sugg = (t.optJSONArray("suggestions") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
            }
            val actions = (t.optJSONArray("actions") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { OryksaTvAction.fromJson(it) } }
            }
            return OryksaTvConfig(
                locale = text(t.optString("locale")) ?: oryksaTvNormalizeLocale(requestedLocale),
                presenterName = text(presenter.optString("name")) ?: text(j.optString("name")) ?: "ORYKSA",
                presenterAvatar = text(presenter.optString("avatar")) ?: text(j.optString("avatar")) ?: DEFAULT_AVATAR,
                voiceRev = text(t.optString("voice_rev")),
                texts = OryksaTvTexts(texts),
                suggestions = sugg,
                actions = actions,
                rules = OryksaTvRules.fromJson(t.optJSONObject("rules")),
                fromServer = tv != null,
            )
        }
    }
}

/** The presenter's voice for one text. */
class OryksaTvSpeech(
    /** MP3 bytes. Empty when the server only counted the line: subtitles only, or a line the device already has. */
    val bytes: ByteArray,
    /** Locale the server spoke in (`X-ORYKSA-Locale`). */
    val locale: String,
    /** Voice revision (`X-ORYKSA-Voice-Rev`). */
    val voiceRev: String? = null,
    /** Interactions this speech used (`X-ORYKSA-Interactions-Spent`). */
    val interactionsSpent: Int? = null,
)

/** A phone pairing: the QR and the code shown on the TV. */
data class OryksaPairing(
    val pairId: String,
    /** Short code the person can type on the phone. */
    val code: String,
    /** What the QR opens. */
    val url: String,
    /** Short address shown under the QR. */
    val shortUrl: String,
    /** PNG of the QR (empty: the TV shows the code only). */
    val qrPngUrl: String,
    /** Seconds to pair before the code expires. */
    val expiresInSec: Int,
    /** When the pairing was created on this device (ms). */
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val expiresAtMs: Long get() = createdAtMs + expiresInSec * 1000L

    companion object {
        /** Reads the answer of `POST /v1/client/tv/pair`. */
        fun fromJson(j: JSONObject): OryksaPairing {
            val id = j.optString("pair_id")
            val code = j.optString("code")
            require(id.isNotBlank() && code.isNotBlank()) { "The pairing has no pair_id or code." }
            val url = j.optString("url")
            return OryksaPairing(
                pairId = id,
                code = code,
                url = url,
                shortUrl = j.optString("short_url").ifBlank { url },
                qrPngUrl = j.optString("qr_png_url"),
                expiresInSec = j.optInt("expires_in", 300),
            )
        }
    }
}

/** Kind of [OryksaPhoneEvent]. */
enum class OryksaPhoneEventType { PAIRED, LISTENING, STOPPED, TEXT, PHONE_LEFT, EXPIRED, CLOSED, UNKNOWN }

/** One event of the phone, from `GET /v1/client/tv/pair/{id}/events`. */
data class OryksaPhoneEvent(
    val seq: Int,
    val type: OryksaPhoneEventType,
    val text: String? = null,
    val lang: String? = null,
    val isFinal: Boolean = true,
) {
    companion object {
        fun fromJson(j: JSONObject) = OryksaPhoneEvent(
            seq = j.optInt("seq", 0),
            type = when (j.optString("type")) {
                "paired" -> OryksaPhoneEventType.PAIRED
                "listening" -> OryksaPhoneEventType.LISTENING
                "stopped" -> OryksaPhoneEventType.STOPPED
                "text" -> OryksaPhoneEventType.TEXT
                "phone_left" -> OryksaPhoneEventType.PHONE_LEFT
                "expired" -> OryksaPhoneEventType.EXPIRED
                "closed" -> OryksaPhoneEventType.CLOSED
                else -> OryksaPhoneEventType.UNKNOWN
            },
            text = j.optString("text").takeIf { j.has("text") && !j.isNull("text") },
            lang = j.optString("lang").takeIf { j.has("lang") && !j.isNull("lang") },
            isFinal = j.optBoolean("final", true),
        )
    }
}

/** One answer of the events long-poll. */
data class OryksaPhoneEventsPage(
    /** `waiting`, `paired`, `expired` or `closed`. */
    val state: String,
    /** Pass as `after` in the next call. */
    val next: Int,
    val events: List<OryksaPhoneEvent> = emptyList(),
    /** Whether a phone is connected right now (`phone`), when the server says it. */
    val phoneConnected: Boolean? = null,
) {
    companion object {
        fun fromJson(j: JSONObject, after: Int = 0): OryksaPhoneEventsPage {
            val a = j.optJSONArray("events") ?: JSONArray()
            val events = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(OryksaPhoneEvent::fromJson) }
            var next = if (j.has("next")) j.optInt("next", after) else after
            for (e in events) if (e.seq > next) next = e.seq
            return OryksaPhoneEventsPage(
                state = j.optString("state").ifBlank { "waiting" },
                next = next,
                events = events,
                phoneConnected = if (j.has("phone") && !j.isNull("phone")) j.optBoolean("phone") else null,
            )
        }
    }
}

// ---- Text for the voice and the subtitles ----

/** The sentences of [text] (ends at `.`, `!`, `?`, `…`, keeping the mark). */
fun oryksaTvSentences(text: String): List<String> {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return emptyList()
    return t.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}

private fun cut(s: String, max: Int): List<String> {
    if (s.length <= max) return listOf(s)
    val out = mutableListOf<String>()
    var rest = s
    while (rest.length > max) {
        var at = rest.lastIndexOf(", ", max)
        if (at > max / 3) at += 1 else {
            at = rest.lastIndexOf(' ', max)
            if (at <= 0) at = max
        }
        out.add(rest.substring(0, at).trim())
        rest = rest.substring(at).trim()
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out
}

/** Splits [text] into pieces of at most [max] characters, at the end of a sentence when possible. */
fun oryksaTvSplitForSpeech(text: String, max: Int): List<String> {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return emptyList()
    if (t.length <= max) return listOf(t)
    val out = mutableListOf<String>()
    var cur = ""
    for (s in oryksaTvSentences(t)) for (piece in cut(s, max)) {
        cur = when {
            cur.isEmpty() -> piece
            cur.length + 1 + piece.length <= max -> "$cur $piece"
            else -> { out.add(cur); piece }
        }
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

/** The subtitle lines of [text]: one sentence per line, long sentences cut at a comma or a space. */
fun oryksaTvSubtitleLines(text: String, max: Int = 90): List<String> = oryksaTvSentences(text).flatMap { cut(it, max) }

/** How long a subtitle stays when there is no voice: about 14 characters a second, never under 1.8 s. */
fun oryksaTvReadingTimeMs(line: String): Long = maxOf(1800L, Math.round(line.length / 14.0 * 1000))
