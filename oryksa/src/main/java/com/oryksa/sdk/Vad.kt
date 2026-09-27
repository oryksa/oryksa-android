package com.oryksa.sdk

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The ORYKSA voice engine: decides WHEN someone started and stopped speaking, and
 * whether a sound while she speaks is a human voice that cuts her off.
 *
 * Line by line port of `lib/vad.dart` of the ORYKSA app (the one engine of the whole
 * ecosystem). Every number is the same and has the same reason; do not change one
 * without running the tests with new real recordings (see `VadTest`).
 *
 * Input: PCM16 little-endian mono chunks at [sampleRate] (16 kHz).
 */
class Vad(val sampleRate: Int = 16000) {

    companion object {
        /** Silence that closes a sentence (450 cut sentences, 900 was too slow; 700 is proven by the tests). */
        const val quietToCloseMs = 700

        /** Minimum speech worth sending. Below this it is a cough or a door. */
        const val minSpeechMs = 500

        /** Minimum recorded audio for the server to have something to hear. */
        const val minAudioMs = 700

        /** Closes the sentence after this, even if the person goes on. */
        const val maxSpeechMs = 15000

        /** VOICE time over her speech needed to silence her (two voiced slices). The pitch test does the heavy work. */
        const val bargeMs = 130

        /** A pause long enough to give up an interruption (typing is ~5 keys a second: 200 ms apart). */
        const val bargeGapMs = 130

        /** Continuous sound, without a single gap, so a loose slice does not count. */
        const val bargeRunMs = 64

        /** How periodic a sound must be to count as voice (keyboard <= 0.20, human voice 0.50 to 0.93). */
        const val bargeVozMin = 0.35

        /** How long the confidence lasts after the last voiced slice (the rest of the word counts). */
        const val vozValeMs = 300

        /**
         * Minimum level to count as an interruption, MEASURED LIVE: her own voice coming back
         * through the microphone is 0.05, the voice of the person at the phone is 0.12 to 0.25.
         * Fixed, never learnt from her echo (that learning also learns the person and makes her deaf).
         */
        const val bargeFloor = 0.090

        /** Highest the LISTEN threshold can go, however noisy (without it she is deaf in a cafe). */
        const val tetoOuvir = 0.10

        /** Highest level that can be taken as the room's silence. */
        const val maxBase = 0.015

        /** Recording time without a pause after which we suspect it is noise. */
        const val noiseAfterMs = 4000

        /** Voice goes up and down much more than this between chunks; background noise does not. */
        const val flatRatio = 2.5

        /** Ceiling of the reference silence. */
        const val maxNoiseBase = 0.12

        /**
         * Is this a human VOICE or a noise? Voice is periodic (vocal folds at 70 to 350 Hz);
         * a key, a clap, a door are clicks with no repetition. Normalised autocorrelation at
         * 8 kHz (every other sample), lags 23 (350 Hz) to 114 (70 Hz).
         */
        @JvmStatic
        fun periodicidade(chunk: ByteArray): Double {
            val n = chunk.size / 2
            if (n < 512) return 0.0
            val m = n / 2
            val x = DoubleArray(m)
            var media = 0.0
            for (i in 0 until m) {
                x[i] = sample(chunk, i * 2) / 32768.0
                media += x[i]
            }
            media /= m
            var energia = 0.0
            for (i in 0 until m) {
                x[i] -= media
                energia += x[i] * x[i]
            }
            if (energia <= 0) return 0.0
            val lagMin = 23
            val lagMax = 114
            var melhor = 0.0
            var lag = lagMin
            while (lag <= lagMax && lag < m) {
                var soma = 0.0
                var i = 0
                while (i + lag < m) {
                    soma += x[i] * x[i + lag]
                    i++
                }
                val r = soma / energia
                if (r > melhor) melhor = r
                lag++
            }
            return melhor
        }

        /** Level of this chunk (0 to 1). */
        @JvmStatic
        fun rmsOf(chunk: ByteArray): Double {
            val n = chunk.size / 2
            if (n == 0) return 0.0
            var sum = 0.0
            for (k in 0 until n) {
                val v = sample(chunk, k) / 32768.0
                sum += v * v
            }
            return sqrt(sum / n)
        }

        /** Sample [index] (PCM16 little endian). */
        private fun sample(b: ByteArray, index: Int): Int {
            val lo = b[index * 2].toInt() and 0xff
            val hi = b[index * 2 + 1].toInt()
            return (hi shl 8) or lo
        }
    }

    private var corrida = 0
    private var maiorCorrida = 0
    private var vozHaMs = 99999

    /** Levels heard in the interruption attempt (for diagnosis). */
    val perfil = ArrayList<Double>()

    /** Peak and pitch of the sentence just recorded (whisper detection and quality report). */
    var picoDaFrase = 0.0
        private set
    var tomDaFrase = 0.0
        private set

    /** A whisper: voice without vocal folds, low and almost without pitch. Contract: peak < 0.09 and pitch < 0.45. */
    val foiSussurro: Boolean get() = picoDaFrase > 0 && picoDaFrase < 0.09 && tomDaFrase < 0.45

    /** Loudest sound while she spoke. */
    var maxEnquantoFala = 0.0
        private set

    /** Highest pitch measured in the interruption attempt. */
    var tomDaInterrupcao = 0.0
        private set

    var base = 0.0
    var frames = 0
    var speechMs = 0
    var quietMs = 0
    var capturing = false

    private var sum = 0.0
    private var minL = 1.0
    private var maxL = 0.0
    private var count = 0

    private fun noise(): VadStep {
        val nivel = if (count > 0) sum / count else base
        base = min(max(base, nivel), maxNoiseBase)
        return VadStep.done(spokeMs = speechMs, enough = false, isNoise = true, level = nivel)
    }

    /** Start from zero, forgetting the learnt silence. Only when a voice session starts. */
    fun reset() {
        base = 0.0
        frames = 0
        newTurn()
    }

    /** Ready for the next sentence, KEEPING the learnt silence (the room does not change between sentences). */
    fun newTurn() {
        corrida = 0
        maiorCorrida = 0
        vozHaMs = 99999
        picoDaFrase = 0.0
        tomDaFrase = 0.0
        tomDaInterrupcao = 0.0
        maxEnquantoFala = 0.0
        perfil.clear()
        speechMs = 0
        quietMs = 0
        capturing = false
        sum = 0.0
        minL = 1.0
        maxL = 0.0
        count = 0
    }

    /** Feeds one chunk. Returns what to do next. */
    fun feed(chunk: ByteArray, speaking: Boolean = false): VadStep {
        val n = chunk.size / 2
        if (n == 0) return VadStep.IDLE
        val rms = rmsOf(chunk)
        val ms = (n * 1000) / sampleRate
        frames++

        // First chunks: learn this place's silence, never above maxBase.
        if (frames <= 3 && !speaking) {
            base = if (base == 0.0) min(rms, maxBase) else min(base, rms)
        }
        // THE TWO THRESHOLDS, different on purpose. CUTTING: the measured floor, not the
        // room noise (x8 took it to 0.29 and a 0.16 voice could not silence her).
        // LISTENING: follows the noise, WITH a ceiling (0.10), or she is deaf in a cafe.
        val speakThr = if (speaking) max(base * 2.0, bargeFloor) else min(max(base * 3.2, 0.014), tetoOuvir)
        val quietThr = max(base * 1.8, 0.008)

        // Room noise: goes down fast, up slowly, never learns from a voice-level sound.
        if (!capturing && !speaking) {
            if (rms < base) {
                base = base * 0.8 + rms * 0.2
            } else if (rms < speakThr) {
                base = base * 0.98 + rms * 0.02
            }
        }

        if (speaking) {
            val thr = speakThr
            if (perfil.size < 40) perfil.add(rms)
            // Only VOICE interrupts. A key click is loud but has no pitch.
            val alto = rms > thr
            if (rms > maxEnquantoFala) maxEnquantoFala = rms
            val tom = if (alto) periodicidade(chunk) else 0.0
            if (tom > tomDaInterrupcao) tomDaInterrupcao = tom
            if (tom >= bargeVozMin) vozHaMs = 0 else vozHaMs += ms
            val ehVoz = alto && vozHaMs <= vozValeMs
            if (ehVoz) {
                speechMs += ms
                quietMs = 0
                corrida += ms
                if (corrida > maiorCorrida) maiorCorrida = corrida
                if (speechMs >= bargeMs && maiorCorrida >= bargeRunMs) return VadStep.BARGE_IN
            } else {
                corrida = 0
                quietMs += ms
                if (quietMs > bargeGapMs) {
                    speechMs = 0
                    quietMs = 0
                    maiorCorrida = 0
                }
            }
            return VadStep.IDLE
        }

        if (rms > speakThr) {
            capturing = true
            speechMs += ms
            quietMs = 0
            if (rms > picoDaFrase) picoDaFrase = rms
            val tf = periodicidade(chunk)
            if (tf > tomDaFrase) tomDaFrase = tf
        } else if (capturing) {
            if (rms < quietThr) quietMs += ms
        }
        if (capturing) {
            sum += rms
            if (rms < minL) minL = rms
            if (rms > maxL) maxL = rms
            count++
        }

        if (!capturing) return VadStep.IDLE

        // Background noise caught early: seconds without a pause AND always at the same level.
        val plano = minL > 0 && maxL < minL * flatRatio
        if (speechMs > noiseAfterMs && quietMs == 0 && plano) return noise()

        if (quietMs <= quietToCloseMs && speechMs <= maxSpeechMs) return VadStep.CAPTURING

        val spoke = speechMs
        val nivel = if (count > 0) sum / count else 0.0

        // Fifteen seconds without ONE pause is not a person: it is constant noise.
        if (spoke > maxSpeechMs && quietMs < 200) return noise()

        return VadStep.done(spokeMs = spoke, enough = spoke >= minSpeechMs, level = nivel)
    }
}

/** What to do with the chunk that just arrived. */
class VadStep private constructor(
    val isCapturing: Boolean,
    val isDone: Boolean,
    val isBargeIn: Boolean,
    val spokeMs: Int,
    /** Spoke enough to be worth sending? */
    val enough: Boolean,
    /** Not a voice: endless background noise. The reference silence went up. */
    val isNoise: Boolean,
    /** Average level of what was recorded. */
    val level: Double,
) {
    companion object {
        val IDLE = VadStep(false, false, false, 0, false, false, 0.0)
        val CAPTURING = VadStep(true, false, false, 0, false, false, 0.0)
        val BARGE_IN = VadStep(false, false, true, 0, false, false, 0.0)
        fun done(spokeMs: Int, enough: Boolean, isNoise: Boolean = false, level: Double = 0.0) =
            VadStep(false, true, false, spokeMs, enough, isNoise, level)
    }
}
