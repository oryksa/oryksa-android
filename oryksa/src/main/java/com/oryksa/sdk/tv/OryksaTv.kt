package com.oryksa.sdk.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.oryksa.sdk.OryksaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ORYKSA TV pieces of one app: the [presenter], the [phoneVoice] and the configuration from the server.
 * Create one with [create] and keep it while the app runs; [close] it at the end.
 *
 * ```kotlin
 * val platform = OryksaTvPlatform.detect(context)?.wire ?: "android"
 * val client = OryksaClient(getToken = { myServerToken() }, platform = platform)
 * val tv = OryksaTv.create(context, client, locale = "pt-BR")
 * scope.launch { tv.presenter.say("Olá! Sou a ORYKSA.") }
 * ```
 */
class OryksaTv(
    /** Where the pieces get their data. */
    val backend: OryksaTvBackend,
    locale: String = oryksaTvLocaleTag(Locale.getDefault()),
    /** Folder of the presenter's voice cache. */
    cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", "oryksa_tv_voice"),
    audioPlayer: OryksaTvAudioPlayer? = null,
    /** Where the pieces run their work. Cancelled by [close]. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    companion object {
        /** The TV pieces on the ORYKSA API through [client]. [locale]: the language of your screen (BCP-47). */
        fun create(context: Context, client: OryksaClient, locale: String? = null): OryksaTv =
            create(context, OryksaTvClientBackend(client), locale)

        /**
         * A client for a TV app WITHOUT a server of its own: a public session from the business's public chat
         * token, with `platform` (Fire TV / Android TV, found on the device) and `app_id` (your package). When the
         * business limits its chat, it must list your package in its allowed apps. `app_id` is a filter, not strong
         * protection (it can be imitated): the strong protection is a token from your own server (`POST /v1/sessions`).
         */
        fun publicClient(context: Context, chatToken: String, conversationId: String, customerName: String? = null): OryksaClient =
            OryksaClient.public(
                chatToken = chatToken,
                conversationId = conversationId,
                customerName = customerName,
                platform = OryksaTvPlatform.detect(context)?.wire ?: "android",
                appId = context.packageName,
            )

        /** The TV pieces on any [backend], for example [OryksaTvFakeBackend] in demos. */
        fun create(context: Context, backend: OryksaTvBackend, locale: String? = null): OryksaTv = OryksaTv(
            backend,
            locale ?: oryksaTvLocaleTag(Locale.getDefault()),
            File(context.applicationContext.cacheDir, "oryksa_tv_voice"),
            OryksaTvMediaPlayer(),
        )
    }

    private val _locale = MutableStateFlow(oryksaTvNormalizeLocale(locale))

    /** The language of the app screen (BCP-47). */
    val locale: StateFlow<String> = _locale.asStateFlow()

    private val _config = MutableStateFlow<OryksaTvConfig?>(null)

    /** The last configuration read from the server, or null before the first one. */
    val config: StateFlow<OryksaTvConfig?> = _config.asStateFlow()

    private val configLock = Mutex()
    private var cached: Pair<String, OryksaTvConfig>? = null
    private var cachedAtMs = 0L

    /**
     * How long the configuration is kept before it is read again (10 minutes). The business may change its voice,
     * texts or rules while the app is open; a new voice_rev then also stops the old cached lines from playing.
     */
    internal var configTtlMs = 10 * 60_000L

    /** Changes the language: the configuration is read again in the new one. */
    fun setLocale(value: String) {
        val l = oryksaTvNormalizeLocale(value)
        if (l == _locale.value) return
        _locale.value = l
        _config.value = null
    }

    /** The TV configuration for [locale], kept for 10 minutes (pass [refresh] to read it again now). */
    suspend fun loadConfig(refresh: Boolean = false): OryksaTvConfig = configLock.withLock {
        val l = _locale.value
        val fresh = System.currentTimeMillis() - cachedAtMs < configTtlMs
        cached?.let { (cl, c) -> if (!refresh && cl == l && fresh) return@withLock c }
        val c = backend.config(l)
        cached = l to c
        cachedAtMs = System.currentTimeMillis()
        if (_locale.value == l) _config.value = c
        c
    }

    /** The configuration is read again on the next use. */
    internal fun markConfigStale() {
        cachedAtMs = 0L
    }

    /** Like [loadConfig], but when the server cannot be reached returns a configuration with the English fallback. */
    suspend fun configOrFallback(): OryksaTvConfig = try {
        loadConfig()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i("OryksaTv", "could not read the configuration ($e); using the fallback.")
        OryksaTvConfig(locale = _locale.value, fromServer = false)
    }

    /** The presenter: the AI speaks on screen with the voice of the locale and big subtitles. */
    val presenter: OryksaPresenter = OryksaPresenter(this, audioPlayer ?: OryksaTvMediaPlayer(), cacheDir)

    /** The phone as the microphone (QR). */
    val phoneVoice: OryksaPhoneVoice = OryksaPhoneVoice(this)

    override fun close() {
        phoneVoice.close()
        presenter.stop()
        scope.cancel()
        presenter.release()
    }
}

/** What the presenter is doing. */
enum class OryksaPresenterPhase { IDLE, PREPARING, SPEAKING }

/** The presenter on screen now: [line] is the subtitle, [text] the whole text she is saying. */
data class OryksaPresenterState(val phase: OryksaPresenterPhase = OryksaPresenterPhase.IDLE, val line: String = "", val text: String = "")

/**
 * The presenter: the AI speaks with the voice of the locale (from the server, never in the code) and big
 * subtitles. Get it from [OryksaTv.presenter] and show it with `OryksaPresenterView`.
 *
 * Every line she says, or only shows, is charged each time by the ORYKSA interaction cost table (per piece of up
 * to 600 characters), a repeated line too: a spoken line at the voice price, a subtitles-only line at the text
 * price. The line appears only once the server has counted it. Lines already said are kept on the device, so the
 * server does not have to make the same voice again.
 */
class OryksaPresenter internal constructor(
    private val tv: OryksaTv,
    private val player: OryksaTvAudioPlayer,
    private val cacheDir: File,
) {
    private val _state = MutableStateFlow(OryksaPresenterState())

    /** What she is doing and the subtitle on screen. */
    val state: StateFlow<OryksaPresenterState> = _state.asStateFlow()

    /** True while she speaks or prepares to. */
    val isBusy: Boolean get() = _state.value.phase != OryksaPresenterPhase.IDLE

    /** Called when she starts speaking (lower your music or video). */
    var onStart: (() -> Unit)? = null

    /** Called when she stops speaking. */
    var onEnd: (() -> Unit)? = null

    /** Called with errors (network, no interactions left, a line the server did not count). A line not counted is not shown. */
    var onError: ((Throwable) -> Unit)? = null

    /** How long a subtitle stays when there is no voice (tests can shorten it). */
    internal var readingTimeMs: (String) -> Long = ::oryksaTvReadingTimeMs

    @Volatile private var job: Job? = null

    /**
     * The voice revision the server sent for each locale (`X-ORYKSA-Voice-Rev`), used for the cache when the
     * configuration has none: without it a kept line was not known as kept and its voice was made again (Fire TV, 26/11/2026).
     */
    private val revByLocale = ConcurrentHashMap<String, String>()

    /**
     * Says [text] (written by your app) in the locale of the TV pieces, with subtitles. Returns when she
     * finishes, is stopped or is given another text. Never throws: errors go to [onError]. Each piece of the
     * text is charged each time, even a line said before (at the voice price); a piece the server did not count is
     * not shown, and she stops there.
     *
     * With [voice] `false` she shows the subtitles only, at reading speed, without any sound (for a TV without
     * sound, at night, or a line that should only be read). Each piece is charged at the text price. OK and Back
     * stop her as usual.
     */
    suspend fun say(text: String, whisper: Boolean = false, voice: Boolean = true) {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val wasIdle = !isBusy
        val previous = job
        if (t.isEmpty()) {
            previous?.cancel()
            job = null
            if (isBusy) toIdle()
            return
        }
        val next = tv.scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { speakAll(t, whisper, voice) }
        job = next
        previous?.cancel()
        player.stop()
        _state.value = OryksaPresenterState(OryksaPresenterPhase.PREPARING, "", t)
        if (wasIdle) onStart?.invoke()
        next.start()
        next.join()
    }

    /**
     * Asks the AI to write a line ([instructions] and [input], as in `client.ai`) in the locale of the TV pieces
     * and says it. Returns the text. Counts the interactions of the AI answer and of the line, as in [say]
     * ([voice] `false`: the subtitles only, counted the same).
     */
    suspend fun sayGenerated(instructions: String, input: String? = null, whisper: Boolean = false, voice: Boolean = true): String {
        val cfg = tv.configOrFallback()
        val r = tv.backend.ai(
            instructions = "$instructions\n\nWrite the answer in the language of the locale ${cfg.locale}, as plain text " +
                "to be spoken aloud (no markdown, no lists, no emojis), at most ${cfg.rules.presenterMaxChars} characters.",
            input = input,
        )
        val text = r.text.trim()
        if (text.isNotEmpty()) say(text, whisper, voice)
        return text
    }

    /** Stops speaking now. */
    fun stop() {
        val j = job
        job = null
        j?.cancel()
        player.stop()
        if (isBusy) toIdle()
    }

    internal fun release() = player.release()

    private fun toIdle() {
        _state.value = OryksaPresenterState()
        onEnd?.invoke()
    }

    private fun show(line: String) {
        _state.value = _state.value.copy(phase = OryksaPresenterPhase.SPEAKING, line = line)
    }

    private suspend fun speakAll(t: String, whisper: Boolean, voice: Boolean) {
        val me = coroutineContext[Job]
        try {
            val cfg = tv.configOrFallback()
            for (chunk in oryksaTvSplitForSpeech(t, cfg.rules.presenterMaxChars)) {
                if (!speakChunk(chunk, cfg, whisper, voice)) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke(e)
        } finally {
            if (job === me) {
                job = null
                toIdle()
            }
        }
    }

    private fun fileFor(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$h.mp3")
    }

    private fun cachedFile(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0 }

    private suspend fun putFile(key: String, bytes: ByteArray): File? = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            val f = fileFor(key)
            f.writeBytes(bytes)
            val all = cacheDir.listFiles { x -> x.name.endsWith(".mp3") }.orEmpty()
            if (all.size > 300) all.sortedBy { it.lastModified() }.take(all.size - 300).forEach { it.delete() }
            f
        }.getOrNull()
    }

    private suspend fun subtitlesOnly(lines: List<String>) {
        for (l in lines) {
            show(l)
            delay(readingTimeMs(l))
        }
    }

    /** Says one piece. False when the server did not count it: then nothing is shown and she stops. */
    private suspend fun speakChunk(chunk: String, cfg: OryksaTvConfig, whisper: Boolean, voice: Boolean): Boolean {
        fun key(rev: String?) = "${rev ?: "-"}|${cfg.locale}|${if (whisper) 1 else 0}|$chunk"
        val rev0 = cfg.voiceRev ?: revByLocale[cfg.locale]
        var file = if (voice) cachedFile(key(rev0)) else null
        // Every piece is asked of the server each time, even one the device already has: each time she says it, it
        // is charged (owner, 26/11/2026). For a kept line the server only charges it, with no new voice.
        val sp = try {
            tv.backend.speak(chunk, cfg.locale, whisper, voice, if (file != null) rev0 else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError?.invoke(e)
            return false
        }
        if (sp == null || (sp.interactionsSpent ?: 0) < 1) {
            onError?.invoke(com.oryksa.sdk.OryksaException(0, "not_counted", "The server did not count this line, so it is not shown."))
            return false
        }
        if (voice && sp.bytes.isNotEmpty()) {
            sp.voiceRev?.takeIf { it.isNotBlank() }?.let { revByLocale[cfg.locale] = it }
            // The server speaks with another voice than the configuration says: the business changed it, so
            // the configuration is read again and the old cached lines stop being used.
            if (sp.voiceRev != null && cfg.voiceRev != null && sp.voiceRev != cfg.voiceRev) tv.markConfigStale()
            file = putFile(key(sp.voiceRev ?: cfg.voiceRev), sp.bytes)
        }
        val lines = oryksaTvSubtitleLines(chunk)
        if (lines.isEmpty()) return true
        // Counted, with no voice (asked for none, or none for this locale): the subtitles only, at reading speed.
        // Never a robot voice.
        if (file == null) {
            subtitlesOnly(lines)
            return true
        }

        var started = false
        try {
            coroutineScope {
                var ticker: Job? = null
                try {
                    player.play(file) { durationMs ->
                        started = true
                        show(lines.first())
                        // Each subtitle line gets its share of the audio by length (or the reading time).
                        val total = lines.sumOf { it.length }.coerceAtLeast(1)
                        ticker = launch {
                            var acc = 0
                            var elapsed = 0L
                            for (i in 1 until lines.size) {
                                acc += lines[i - 1].length
                                val at = if (durationMs != null && durationMs > 0) durationMs * acc / total
                                else lines.take(i).sumOf { readingTimeMs(it) }
                                delay((at - elapsed).coerceAtLeast(0))
                                elapsed = at
                                show(lines[i])
                            }
                        }
                    }
                } finally {
                    ticker?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError?.invoke(e)
            if (!started) subtitlesOnly(lines)
        }
        return true
    }
}

/** Plays the presenter's voice. The default is [OryksaTvMediaPlayer]; tests pass their own. */
interface OryksaTvAudioPlayer {
    /** Plays [file]. Calls [onStart] with the length in ms (null when unknown) once it starts; returns when it ends. */
    suspend fun play(file: File, onStart: (durationMs: Long?) -> Unit)

    /** Stops what is playing. */
    fun stop()

    /** Frees the player. */
    fun release()
}

/** The presenter's voice with Android's MediaPlayer, from the cached file, as media. */
class OryksaTvMediaPlayer : OryksaTvAudioPlayer {
    @Volatile private var current: MediaPlayer? = null

    override suspend fun play(file: File, onStart: (durationMs: Long?) -> Unit) = suspendCancellableCoroutine { cont ->
        val p = MediaPlayer()
        current = p
        fun finish(error: Throwable? = null) {
            if (current === p) current = null
            runCatching { p.release() }
            if (cont.isActive) { if (error == null) cont.resume(Unit) else cont.resumeWithException(error) }
        }
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                onStart(it.duration.toLong().takeIf { d -> d > 0 })
                it.start()
            }
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, what, extra -> finish(IOException("MediaPlayer error $what/$extra")); true }
            p.prepareAsync()
        } catch (e: Exception) {
            finish(e)
        }
        cont.invokeOnCancellation {
            runCatching { p.stop() }
            finish()
        }
    }

    override fun stop() {
        current?.let { runCatching { it.stop() } }
    }

    override fun release() {
        current?.let { runCatching { it.release() } }
        current = null
    }
}

/** The TV's own microphone. */
object OryksaTvMic {
    /**
     * True when the device lists at least one audio input for apps (`AudioManager.getDevices(GET_DEVICES_INPUTS)`).
     * It never asks for the permission: on the Fire TV Stick the system denies it by itself and lists no input.
     * A listed input is not yet proof of sound: check what you hear before you rely on it.
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).isNotEmpty()
    }.getOrDefault(false)
}
