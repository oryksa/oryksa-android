package com.oryksa.sdk.tv

import com.oryksa.sdk.OryksaAiResult
import com.oryksa.sdk.OryksaAppContext
import com.oryksa.sdk.OryksaClient
import com.oryksa.sdk.OryksaException
import com.oryksa.sdk.OryksaReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * What the TV pieces need from ORYKSA. The real one is [OryksaTvClientBackend]; tests and demos use
 * [OryksaTvFakeBackend]. Your app never changes when one is swapped for the other.
 */
interface OryksaTvBackend {
    /** The value sent as `platform` (for example `fire_tv`). */
    val platform: String

    /** The TV configuration for [locale]. The server picks the supported locale and says which. */
    suspend fun config(locale: String): OryksaTvConfig

    /**
     * The presenter's line [text] in [locale], charged each time by the ORYKSA interaction cost table
     * ([OryksaTvSpeech.interactionsSpent]; a line not counted is not shown). [voice] `false`: subtitles only, no voice
     * wanted. [cachedRev]: the device already has this line with that voice revision, so the server may only count it
     * and send no voice. The bytes may be empty (subtitles only). Null when the server gave nothing for it. Throws on
     * other errors.
     */
    suspend fun speak(text: String, locale: String, whisper: Boolean = false, voice: Boolean = true, cachedRev: String? = null): OryksaTvSpeech?

    /** Sends a message to the AI and waits for the reply (the assistant panel). */
    suspend fun chat(message: String, locale: String, appContext: OryksaAppContext? = null, voice: Boolean = false): OryksaReply

    /** An AI task of your app (`client.ai`), counted as ORYKSA interactions. */
    suspend fun ai(instructions: String? = null, input: String? = null, schema: JSONObject? = null): OryksaAiResult

    /** Starts pairing a phone: the QR and the code to show. */
    suspend fun pair(locale: String): OryksaPairing

    /** Waits up to [waitSec] for phone events newer than [after] (long-poll). */
    suspend fun phoneEvents(pairId: String, after: Int = 0, waitSec: Int = 25): OryksaPhoneEventsPage

    /** Ends the pairing. */
    suspend fun unpair(pairId: String)
}

/**
 * The real backend: the ORYKSA API through an [OryksaClient] (its session token, its `platform`).
 * Written against the contract in the TV SDK design (section 9).
 */
class OryksaTvClientBackend(val client: OryksaClient) : OryksaTvBackend {
    override val platform: String get() = client.platform

    private fun q(v: String) = URLEncoder.encode(v, "UTF-8")

    override suspend fun config(locale: String): OryksaTvConfig {
        val l = oryksaTvNormalizeLocale(locale)
        val j = client.request("GET", "/client/agent" + if (l.isEmpty()) "" else "?locale=${q(l)}")
        return OryksaTvConfig.fromAgentJson(j, l)
    }

    override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?): OryksaTvSpeech? {
        val l = oryksaTvNormalizeLocale(locale)
        val body = JSONObject().put("text", text).put("purpose", "presenter").put("platform", platform)
        if (l.isNotEmpty()) body.put("locale", l)
        if (whisper) body.put("whisper", true)
        // Subtitles only: the server counts the line and sends no voice.
        if (!voice) body.put("voice", false)
        // The device has this line with this voice: the server counts it and need not make the voice again.
        if (voice && !cachedRev.isNullOrEmpty()) body.put("cached", true).put("voice_rev", cachedRev)
        return try {
            val a = client.requestAudio("/client/tts", body)
            OryksaTvSpeech(
                a.bytes,
                locale = a.headers["x-oryksa-locale"] ?: l,
                voiceRev = a.headers["x-oryksa-voice-rev"],
                interactionsSpent = a.headers["x-oryksa-interactions-spent"]?.toIntOrNull(),
            )
        } catch (e: OryksaException) {
            if (e.code == "voice_unavailable") null else throw e
        }
    }

    override suspend fun chat(message: String, locale: String, appContext: OryksaAppContext?, voice: Boolean): OryksaReply =
        client.sendAndWaitReply(message, appContext = appContext, voice = voice, lang = oryksaTvChatLang(locale).ifBlank { null })

    override suspend fun ai(instructions: String?, input: String?, schema: JSONObject?): OryksaAiResult =
        client.ai(instructions = instructions, input = input, schema = schema)

    override suspend fun pair(locale: String): OryksaPairing {
        val l = oryksaTvNormalizeLocale(locale)
        val body = JSONObject().put("platform", platform)
        if (l.isNotEmpty()) body.put("locale", l)
        return OryksaPairing.fromJson(client.request("POST", "/client/tv/pair", body))
    }

    override suspend fun phoneEvents(pairId: String, after: Int, waitSec: Int): OryksaPhoneEventsPage {
        // The server holds the request for up to `wait` (at most 30 s): keep it under the client's limit too.
        val wait = waitSec.coerceAtMost(client.timeoutMillis / 1000 - 5).coerceAtMost(30).coerceAtLeast(1)
        val j = client.request("GET", "/client/tv/pair/${q(pairId)}/events?after=$after&wait=$wait")
        return OryksaPhoneEventsPage.fromJson(j, after)
    }

    override suspend fun unpair(pairId: String) {
        client.request("DELETE", "/client/tv/pair/${q(pairId)}")
    }
}

/**
 * SIMULATED backend, for tests and demos only. It never calls ORYKSA, never counts interactions and its texts
 * are samples: nothing shown with it proves the real server. It follows the live server's behaviour for the
 * pairing (codes, `phone`, a new pairing closes the previous one, 410 after the end).
 *
 * Drive the phone with [phoneConnects], [phoneSays], [phoneLeaves] and [expire].
 */
class OryksaTvFakeBackend(
    override val platform: String = "android_tv",
    /** The MP3 for a text, or null for "no voice" (the default: the line is counted and shows the subtitles only). */
    private val voice: ((text: String, locale: String) -> ByteArray?)? = null,
    private val chatReply: (String) -> String = { "(simulated) You said: \"$it\"" },
    /** A fixed configuration (null: the sample one for the asked locale). */
    private val fixedConfig: OryksaTvConfig? = null,
) : OryksaTvBackend {
    /** Calls to [speak] (each one is charged on the real server). */
    @Volatile var speakCalls = 0
        private set

    /** What one [speak] asked: the text, whether a voice was wanted, and the voice revision of a line the device had. */
    data class SpeakRequest(val text: String, val voice: Boolean, val cachedRev: String?)

    /** Every [speak], in order. */
    val speakRequests: MutableList<SpeakRequest> = java.util.Collections.synchronizedList(mutableListOf())

    /** Messages sent with [chat]. */
    val sentMessages: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Calls to [ai]. */
    @Volatile var aiCalls = 0
        private set

    /** Pairings ended with [unpair]. */
    val unpaired: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val lock = Any()
    private var pairs = 0
    private var pairId: String? = null
    private var seq = 0
    private var state = "waiting"
    private var phone = false
    private val events = mutableListOf<OryksaPhoneEvent>()
    private var wake = CompletableDeferred<Unit>()

    companion object {
        /** Codes of the simulated pairings, in order (the real alphabet: no I, O, 0 or 1). */
        val CODES = listOf("K7Q2MZ", "H4PRWX", "T9GBNE", "D3VJSL")

        /** The pair id of the [n]-th simulated pairing (1-based): `tvp_` and 12 hex, as the server. */
        fun pairIdOf(n: Int): String = "tvp_" + (0x5e1000000000L + n).toString(16)

        /** The locale the real server would choose (the 8 voices; the rest English of the USA). */
        fun resolveLocale(requested: String): String {
            val l = oryksaTvNormalizeLocale(requested)
            val lang = l.substringBefore('-')
            val region = if (l.contains('-')) l.substringAfterLast('-') else ""
            val latam = setOf("MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PA", "UY", "PR", "419", "US")
            return when (lang) {
                "pt" -> if (region == "BR") "pt-BR" else "pt-PT"
                "es" -> if (region in latam) "es-419" else "es-ES"
                "fr" -> "fr-FR"
                "de" -> "de-DE"
                "en" -> if (region == "GB") "en-GB" else "en-US"
                else -> "en-US"
            }
        }

        /**
         * Sample texts (English, and Portuguese of Brazil and of Portugal, never mixed): the real ones come from the
         * server. Other languages use English, the only fallback language.
         */
        val SAMPLE_TEXTS: Map<String, Map<String, String>> = mapOf(
            "en" to OryksaTvTexts.FALLBACK + ("assistant_title" to "(simulated) Talk to ORYKSA"),
            "pt-PT" to mapOf(
                "assistant_title" to "(simulado) Fala com a ORYKSA",
                "assistant_hint" to "Escolhe uma pergunta ou fala pelo telemóvel",
                "you" to "Tu",
                "thinking" to "A pensar…",
                "pair_title" to "Fala pelo telemóvel",
                "pair_step_scan" to "Aponta a câmara do telemóvel para o código",
                "pair_step_code" to "Ou abre {url} e escreve {code}",
                "pair_waiting" to "À espera do telemóvel…",
                "pair_connected" to "Telemóvel ligado",
                "pair_expired" to "O código expirou",
                "listening" to "A ouvir…",
                "phone_left" to "O telemóvel desligou-se",
                "open_on_phone" to "Abre no telemóvel",
                "retry" to "Tentar de novo",
                "close" to "Fechar",
                "error" to "Não deu. Tenta de novo.",
                "type_message" to "Escreve uma mensagem",
                "send" to "Enviar",
            ),
            "pt-BR" to mapOf(
                "assistant_title" to "(simulado) Fale com a ORYKSA",
                "assistant_hint" to "Escolha uma pergunta ou fale pelo celular",
                "you" to "Você",
                "thinking" to "Pensando…",
                "pair_title" to "Fale pelo celular",
                "pair_step_scan" to "Aponte a câmera do celular para o código",
                "pair_step_code" to "Ou abra {url} e digite {code}",
                "pair_waiting" to "Esperando o celular…",
                "pair_connected" to "Celular conectado",
                "pair_expired" to "O código expirou",
                "listening" to "Ouvindo…",
                "phone_left" to "O celular desconectou",
                "open_on_phone" to "Abra no celular",
                "retry" to "Tentar de novo",
                "close" to "Fechar",
                "error" to "Não deu certo. Tente de novo.",
                "type_message" to "Digitar uma mensagem",
                "send" to "Enviar",
            ),
        )

        /** The sample texts for a supported locale ([resolveLocale]): pt-BR, pt-PT, otherwise English. */
        fun sampleTextsFor(locale: String): Map<String, String> = SAMPLE_TEXTS[locale] ?: SAMPLE_TEXTS.getValue("en")
    }

    override suspend fun config(locale: String): OryksaTvConfig {
        fixedConfig?.let { return it }
        val l = resolveLocale(locale)
        return OryksaTvConfig(
            locale = l,
            voiceRev = "sim-1",
            texts = OryksaTvTexts(sampleTextsFor(l)),
            suggestions = when (l) {
                "pt-BR" -> listOf("Como funciona?", "O que você pode fazer?")
                "pt-PT" -> listOf("Como funciona?", "O que podes fazer?")
                else -> listOf("How does it work?", "What can you do?")
            },
            actions = listOf(OryksaTvAction("phone_voice", OryksaTvActionKind.PHONE_VOICE, when (l) {
                "pt-BR" -> "Falar pelo celular"
                "pt-PT" -> "Falar pelo telemóvel"
                else -> "Talk on the phone"
            })),
        )
    }

    override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?): OryksaTvSpeech? {
        speakCalls++
        speakRequests.add(SpeakRequest(text, voice, cachedRev))
        val l = resolveLocale(locale)
        // As the server should: every line is charged (1 here); with no voice wanted, a line the device already has (same
        // revision) or no voice for the locale, it only counts it and sends no voice.
        val none = OryksaTvSpeech(ByteArray(0), l, "sim-1", 1)
        if (!voice || cachedRev == "sim-1") return none
        val b = this.voice?.invoke(text, l)
        if (b == null || b.isEmpty()) return none
        return OryksaTvSpeech(b, l, "sim-1", 1)
    }

    override suspend fun chat(message: String, locale: String, appContext: OryksaAppContext?, voice: Boolean): OryksaReply {
        sentMessages.add(message)
        val r = chatReply(message)
        return OryksaReply("replied", r, null, speech = if (voice) r else null)
    }

    override suspend fun ai(instructions: String?, input: String?, schema: JSONObject?): OryksaAiResult {
        aiCalls++
        return OryksaAiResult(text = "(simulated) ${input ?: instructions ?: ""}".trim(), json = null, interactionsSpent = 1, interactionsRemaining = null)
    }

    override suspend fun pair(locale: String): OryksaPairing = synchronized(lock) {
        pairs++
        pairId = pairIdOf(pairs)
        seq = 0
        state = "waiting"
        phone = false
        events.clear()
        val code = CODES[(pairs - 1) % CODES.size]
        OryksaPairing(pairId!!, code, "https://oryksa.com/tv?c=$code", "oryksa.com/tv", "", 300)
    }

    /** Adds an event to the pairing now; with [onlyFor], only when that is still the pairing now. */
    private fun push(type: OryksaPhoneEventType, text: String? = null, newState: String? = null, onlyFor: String? = null) {
        val w: CompletableDeferred<Unit>
        synchronized(lock) {
            if (onlyFor != null && onlyFor != pairId) return
            events.add(OryksaPhoneEvent(++seq, type, text, if (text == null) null else "pt-PT"))
            if (newState != null) state = newState
            if (type == OryksaPhoneEventType.PAIRED) phone = true
            if (type == OryksaPhoneEventType.PHONE_LEFT || type == OryksaPhoneEventType.CLOSED) phone = false
            w = wake
            wake = CompletableDeferred()
        }
        w.complete(Unit)
    }

    /** The phone opened the page. */
    fun phoneConnects() = push(OryksaPhoneEventType.PAIRED, newState = "paired")

    /** The person said [text] on the phone (a `listening` then a `text` event). */
    fun phoneSays(text: String) {
        push(OryksaPhoneEventType.LISTENING)
        push(OryksaPhoneEventType.TEXT, text)
    }

    /** The phone closed the page. */
    fun phoneLeaves() = push(OryksaPhoneEventType.PHONE_LEFT)

    /** The code expired. */
    fun expire() = push(OryksaPhoneEventType.EXPIRED, newState = "expired")

    override suspend fun phoneEvents(pairId: String, after: Int, waitSec: Int): OryksaPhoneEventsPage {
        val w: CompletableDeferred<Unit>
        synchronized(lock) {
            if (pairId != this.pairId) return OryksaPhoneEventsPage("closed", after)
            val ended = state == "expired" || state == "closed"
            if (ended && after >= seq) throw OryksaException(410, "pair_expired", "The pairing has ended.")
            val fresh = events.filter { it.seq > after }
            if (fresh.isNotEmpty() || ended) return OryksaPhoneEventsPage(state, fresh.lastOrNull()?.seq ?: after, fresh, phone)
            w = wake
        }
        withTimeoutOrNull(waitSec.coerceIn(0, 30) * 1000L) { w.await() }
        synchronized(lock) {
            val fresh = events.filter { it.seq > after }
            return OryksaPhoneEventsPage(state, fresh.lastOrNull()?.seq ?: after, fresh, phone)
        }
    }

    override suspend fun unpair(pairId: String) {
        unpaired.add(pairId)
        // Only the pairing it names, checked under the lock: checked outside it, the unpair of the old pairing in
        // restart() could close the new one being made at the same time (16 of 400 runs, 27/11/2026).
        push(OryksaPhoneEventType.CLOSED, newState = "closed", onlyFor = pairId)
    }
}
