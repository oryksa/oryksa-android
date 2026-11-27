package com.oryksa.sdk.tv

import com.oryksa.sdk.OryksaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the phone voice is. */
enum class OryksaPhoneVoicePhase { IDLE, CREATING, WAITING, PAIRED, LISTENING, PHONE_LEFT, EXPIRED, CLOSED, ERROR }

/** The phone voice now: [pairing] is the QR on screen; [lastText] the last thing heard. */
data class OryksaPhoneVoiceState(
    val phase: OryksaPhoneVoicePhase = OryksaPhoneVoicePhase.IDLE,
    val pairing: OryksaPairing? = null,
    val lastText: String = "",
    val error: Throwable? = null,
) {
    /** True from start until stop (or the code expired or the pairing ended). */
    val isActive: Boolean
        get() = phase in setOf(
            OryksaPhoneVoicePhase.CREATING, OryksaPhoneVoicePhase.WAITING, OryksaPhoneVoicePhase.PAIRED,
            OryksaPhoneVoicePhase.LISTENING, OryksaPhoneVoicePhase.PHONE_LEFT,
        )
}

/**
 * The phone as the microphone: the TV shows a QR, the phone opens the ORYKSA page and what the person says arrives
 * here as text ([texts], [onText]). For TVs that give no microphone to apps (for example the Fire TV Stick).
 * Get it from [OryksaTv.phoneVoice] and show it with `OryksaPairingCard`.
 */
class OryksaPhoneVoice internal constructor(private val tv: OryksaTv) {
    private val _state = MutableStateFlow(OryksaPhoneVoiceState())

    /** Where it is. */
    val state: StateFlow<OryksaPhoneVoiceState> = _state.asStateFlow()

    private val _texts = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Each text the person said on the phone. */
    val texts: SharedFlow<String> = _texts.asSharedFlow()

    /** Called with each text the person said on the phone. */
    var onText: ((String) -> Unit)? = null

    /** How long each events request waits on the server (long-poll), in seconds. */
    internal var pollWaitSec = 25

    /** Pause before asking again after a network error. */
    internal var retryPauseMs = 2_000L

    @Volatile private var gen = 0
    @Volatile private var job: Job? = null

    /** Asks the server for a code and waits for the phone. Does nothing while already active. */
    fun start() {
        if (_state.value.isActive) return
        val my = ++gen
        _state.value = OryksaPhoneVoiceState(OryksaPhoneVoicePhase.CREATING)
        job = tv.scope.launch { run(my) }
    }

    /** Ends the pairing (the phone page stops sending). */
    fun stop() {
        gen++
        job?.cancel()
        job = null
        val p = _state.value.pairing
        _state.value = OryksaPhoneVoiceState()
        if (p != null) tv.scope.launch { runCatching { tv.backend.unpair(p.pairId) } }
    }

    /** A new code: ends the current pairing and starts another. */
    fun restart() {
        stop()
        start()
    }

    internal fun close() {
        gen++
        job?.cancel()
        job = null
    }

    private fun set(my: Int, change: (OryksaPhoneVoiceState) -> OryksaPhoneVoiceState) {
        if (my == gen) _state.value = change(_state.value)
    }

    private fun end(my: Int, phase: OryksaPhoneVoicePhase) {
        set(my) { it.copy(phase = phase) }
        if (my == gen) gen++
    }

    private suspend fun run(my: Int) {
        val p = try {
            tv.backend.pair(tv.locale.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            set(my) { it.copy(phase = OryksaPhoneVoicePhase.ERROR, error = e) }
            return
        }
        if (my != gen) {
            runCatching { tv.backend.unpair(p.pairId) }
            return
        }
        set(my) { it.copy(phase = OryksaPhoneVoicePhase.WAITING, pairing = p) }
        coroutineScope {
            val expiry = launch {
                delay((p.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                if (my == gen && _state.value.phase == OryksaPhoneVoicePhase.WAITING) end(my, OryksaPhoneVoicePhase.EXPIRED)
            }
            try {
                poll(my, p, expiry)
            } finally {
                expiry.cancel()
            }
        }
    }

    private suspend fun poll(my: Int, p: OryksaPairing, expiry: Job) {
        var after = 0
        while (my == gen) {
            val page = try {
                tv.backend.phoneEvents(p.pairId, after, pollWaitSec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OryksaException) {
                if (my != gen) return
                if (e.code == "pair_expired" || e.status == 410) return end(my, OryksaPhoneVoicePhase.EXPIRED)
                if (e.code == "pair_not_found" || e.status == 404) return end(my, OryksaPhoneVoicePhase.CLOSED)
                delay(retryPauseMs)
                continue
            } catch (e: Exception) {
                if (my != gen) return
                delay(retryPauseMs)
                continue
            }
            if (my != gen) return
            after = page.next
            for (e in page.events) {
                if (my != gen) return
                handle(my, e, expiry)
            }
            if (my != gen) return
            if (page.state == "expired") return end(my, OryksaPhoneVoicePhase.EXPIRED)
            if (page.state == "closed") return end(my, OryksaPhoneVoicePhase.CLOSED)
        }
    }

    private fun handle(my: Int, e: OryksaPhoneEvent, expiry: Job) {
        when (e.type) {
            OryksaPhoneEventType.PAIRED -> {
                expiry.cancel()
                set(my) { it.copy(phase = OryksaPhoneVoicePhase.PAIRED) }
            }
            OryksaPhoneEventType.LISTENING -> set(my) { it.copy(phase = OryksaPhoneVoicePhase.LISTENING) }
            OryksaPhoneEventType.STOPPED -> set(my) { it.copy(phase = OryksaPhoneVoicePhase.PAIRED) }
            OryksaPhoneEventType.TEXT -> {
                val t = e.text?.trim().orEmpty()
                set(my) { it.copy(phase = OryksaPhoneVoicePhase.PAIRED, lastText = if (t.isNotEmpty() && e.isFinal) t else it.lastText) }
                if (t.isNotEmpty() && e.isFinal) {
                    onText?.invoke(t)
                    _texts.tryEmit(t)
                }
            }
            OryksaPhoneEventType.PHONE_LEFT -> set(my) { it.copy(phase = OryksaPhoneVoicePhase.PHONE_LEFT) }
            OryksaPhoneEventType.EXPIRED -> end(my, OryksaPhoneVoicePhase.EXPIRED)
            OryksaPhoneEventType.CLOSED -> end(my, OryksaPhoneVoicePhase.CLOSED)
            OryksaPhoneEventType.UNKNOWN -> Unit
        }
    }
}
