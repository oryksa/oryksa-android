package com.oryksa.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the voice conversation is doing now. */
enum class OryksaVoicePhase { STARTING, LISTENING, HEARING, THINKING, SPEAKING, MUTED, MIC_ERROR, NOISY, NOT_UNDERSTOOD }

/**
 * The ORYKSA voice conversation (the same behaviour as the ORYKSA app and every other ORYKSA channel):
 * the microphone stays open even while she speaks; only a human voice cuts her off and she stops on the
 * word (the text stays in the chat); the last second before the cut is kept; 700 ms of silence closes a
 * sentence, 15 s is the longest; a whisper gets a whispered, shorter answer; her voice is the ElevenLabs
 * voice chosen in ORYKSA, and if it fails she stays silent and the text is shown (never a robot voice).
 *
 * Needs the RECORD_AUDIO runtime permission (the SDK manifest declares it; [OryksaVoiceScreen] asks for it).
 */
class OryksaVoiceController(
    context: Context,
    val client: OryksaClient,
    var appContext: (() -> OryksaAppContext?)? = null,
    var onUserText: ((String) -> Unit)? = null,
    var onReply: ((String) -> Unit)? = null,
) {
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sr = 16000
    private val preRollBytes = sr * 2

    val vad = Vad(sr)

    private val _phase = MutableStateFlow(OryksaVoicePhase.STARTING)
    /** Current phase (the voice screen shows it). */
    val phase: StateFlow<OryksaVoicePhase> = _phase
    /** Last thing the AI said. */
    val lastReply = MutableStateFlow("")
    /** Last thing the customer said. */
    val lastHeard = MutableStateFlow("")

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var readJob: Job? = null
    private var player: MediaPlayer? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var oldMode = AudioManager.MODE_NORMAL

    private var pcm = ByteArrayOutputStream()
    private val beforeCut = ArrayDeque<ByteArray>()
    private var beforeCutBytes = 0
    private var noEffects = false
    private var heardSomething = false
    private var closed = false
    private var speaking = false
    private var busy = false
    private var cutThisTurn = false
    private var whispered = false
    private var noiseInARow = 0
    private var turn = 0

    private fun set(p: OryksaVoicePhase) { if (!closed) _phase.value = p }

    /** Opens the microphone and starts listening. */
    fun start() {
        if (closed || readJob != null || _phase.value == OryksaVoicePhase.MUTED) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            set(OryksaVoicePhase.MIC_ERROR); return
        }
        oldMode = audio.mode
        // Conversation mode: the system echo cancellation lets her hear the customer while she speaks.
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = true } }
        if (!openMic(effects = !noEffects) && !noEffects) {
            noEffects = true
            if (!openMic(effects = false)) { set(OryksaVoicePhase.MIC_ERROR); return }
        }
        vad.reset()
        pcm = ByteArrayOutputStream()
        heardSomething = false
        set(OryksaVoicePhase.LISTENING)
        val rec = record ?: return
        readJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { delay(10); continue }
                val chunk = buf.copyOf(n)
                withContext(Dispatchers.Main) { onAudio(chunk) }
            }
        }
        scope.launch {
            delay(4000)
            if (!closed && !heardSomething && _phase.value != OryksaVoicePhase.MUTED) micIsDead()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(effects: Boolean): Boolean = try {
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (effects) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val r = AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); false } else {
            if (effects) {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
            }
            r.startRecording()
            record = r
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Some phones deliver digital silence with the system effects on: retry once without them. */
    private fun micIsDead() {
        if (closed) return
        if (!noEffects) {
            noEffects = true
            stopMic()
            start()
            return
        }
        set(OryksaVoicePhase.MIC_ERROR)
    }

    private fun stopMic() {
        readJob?.cancel(); readJob = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
    }

    private fun onAudio(chunk: ByteArray) {
        if (closed || _phase.value == OryksaVoicePhase.MUTED || chunk.size < 2) return
        if (!heardSomething && Vad.rmsOf(chunk) > 0.0005) heardSomething = true

        // While she SPEAKS the engine only decides whether she was cut off.
        if (speaking) {
            beforeCut.addLast(chunk)
            beforeCutBytes += chunk.size
            while (beforeCutBytes > preRollBytes && beforeCut.size > 1) beforeCutBytes -= beforeCut.removeFirst().size
            if (vad.feed(chunk, speaking = true).isBargeIn) bargeIn()
            return
        }

        val step = vad.feed(chunk)
        if (step.isBargeIn) { bargeIn(); return }
        if (step.isCapturing || vad.capturing) {
            pcm.write(chunk)
            if (!busy) set(OryksaVoicePhase.HEARING)
        }
        if (!step.isDone) return

        val raw = pcm.toByteArray()
        pcm = ByteArrayOutputStream()
        val whisper = vad.foiSussurro
        val stats = stats(empty = false, durMs = raw.size / 32)
        vad.newTurn()
        when {
            step.enough && raw.size > sr / 2 -> { noiseInARow = 0; handle(wav(raw, sr), whisper, stats) }
            step.isNoise -> { if (++noiseInARow >= 2) set(OryksaVoicePhase.NOISY) }
            !busy -> set(OryksaVoicePhase.LISTENING)
        }
    }

    /** The customer cut her off: stop the voice on the word, keep what he says. */
    private fun bargeIn() {
        turn++
        speaking = false
        busy = false
        runCatching { player?.stop() }
        vad.newTurn()
        vad.capturing = true
        pcm = ByteArrayOutputStream()
        beforeCut.forEach { pcm.write(it) }
        beforeCut.clear()
        beforeCutBytes = 0
        cutThisTurn = true
        set(OryksaVoicePhase.HEARING)
    }

    /** Sends what was said right away (tap on the picture). */
    fun sendNow() {
        if (busy || speaking || !vad.capturing) return
        val raw = pcm.toByteArray()
        val whisper = vad.foiSussurro
        val st = stats(empty = false, durMs = raw.size / 32)
        vad.newTurn()
        pcm = ByteArrayOutputStream()
        if (raw.size > sr / 2) handle(wav(raw, sr), whisper, st)
    }

    /** Audio numbers of this turn, in the ORYKSA ecosystem format (quality report). */
    private fun stats(empty: Boolean, durMs: Int) = JSONObject()
        .put("channel", "sdk_android")
        .put("peak", Math.round(vad.picoDaFrase * 1000) / 1000.0)
        .put("pitch", Math.round(vad.tomDaFrase * 1000) / 1000.0)
        .put("noise", Math.round(vad.base * 1000) / 1000.0)
        .put("floor", Vad.bargeFloor)
        .put("cut", false)
        .put("whisper", vad.foiSussurro)
        .put("self_cut", cutThisTurn)
        .put("empty", empty)
        .put("dur_ms", durMs)

    private fun handle(wav: ByteArray, whisper: Boolean, stats: JSONObject) {
        val my = ++turn
        whispered = whisper
        busy = true
        set(OryksaVoicePhase.THINKING)
        scope.launch {
            val text = client.transcribe(wav)
            if (closed || my != turn) return@launch
            if (text.isNullOrEmpty()) {
                client.voiceStats(stats.put("empty", true))
                busy = false
                set(if (text == null) OryksaVoicePhase.NOT_UNDERSTOOD else OryksaVoicePhase.LISTENING)
                return@launch
            }
            lastHeard.value = text
            onUserText?.invoke(text)
            val r = runCatching {
                client.sendAndWaitReply(text, appContext = appContext?.invoke(), voice = true, whisper = whisper, voiceStats = stats)
            }.getOrNull()
            if (closed || my != turn) return@launch
            val reply = r?.reply?.trim().orEmpty()
            if (reply.isEmpty()) { busy = false; set(OryksaVoicePhase.NOT_UNDERSTOOD); return@launch }
            lastReply.value = reply
            onReply?.invoke(reply)
            speak(r?.speech?.trim()?.takeIf { it.isNotEmpty() } ?: reply, my)
        }
    }

    /** Speaks [text] with the AI's voice (for example a greeting). If the voice fails she stays silent. */
    fun say(text: String) { val my = ++turn; scope.launch { speak(text, my) } }

    private suspend fun speak(text: String, my: Int) {
        beforeCut.clear()
        beforeCutBytes = 0
        val mp3 = client.tts(text, whispered)
        if (closed || my != turn || mp3 == null) { done(my); return } // never a robot voice: silence + text
        try {
            runCatching { player?.release() }
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(BytesSource(mp3))
            p.setOnCompletionListener { done(my) }
            p.setOnErrorListener { _, _, _ -> done(my); true }
            p.setOnPreparedListener {
                if (closed || my != turn) { done(my); return@setOnPreparedListener }
                speaking = true
                set(OryksaVoicePhase.SPEAKING)
                it.start()
            }
            player = p
            p.prepareAsync()
        } catch (_: Exception) {
            done(my)
        }
    }

    private fun done(my: Int) {
        cutThisTurn = false
        speaking = false
        busy = false
        vad.newTurn()
        if (closed || my != turn) return
        set(OryksaVoicePhase.LISTENING)
    }

    /** Mute: she stops talking and listening. Nothing is lost: what she said stays in the chat. */
    fun toggleMute() {
        if (_phase.value == OryksaVoicePhase.MUTED) {
            _phase.value = OryksaVoicePhase.STARTING
            start()
            return
        }
        turn++
        speaking = false
        busy = false
        runCatching { player?.stop() }
        stopMic()
        vad.newTurn()
        _phase.value = OryksaVoicePhase.MUTED
    }

    /** Closes the microphone and the player. */
    fun close() {
        if (closed) return
        closed = true
        turn++
        runCatching { player?.release() }
        player = null
        stopMic()
        runCatching { @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = false }; audio.mode = oldMode }
        scope.cancel()
    }

    /** Plays the MP3 bytes of the AI's voice without writing a file. */
    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    companion object {
        /** 16 kHz mono PCM16 WAV (what the server transcribes). */
        @JvmStatic
        fun wav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(pcm.size)
            return h.array() + pcm
        }
    }
}
