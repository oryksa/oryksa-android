package com.oryksa.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The audio tests of the ORYKSA app (the test folder, Dart), ported with the engine. Real
 * recordings (the wav files in resources/voice) and synthetic sound: a bird, the TV, typing,
 * a plane, her own echo must NOT cut her off; "Para" and "Espera" MUST.
 */
class VadTest {

    // ------------------------------------------------------------------ helpers
    /** [ms] of sound at exactly [nivel] RMS (16 kHz PCM16). With [tom] it is periodic like a voice. */
    private fun pedaco(nivel: Double, ms: Int = 100, tom: Boolean = true, hz: Int = 120, seed: Int = 7): ByteArray {
        val n = 16000 * ms / 1000
        val r = Random(seed)
        val onda = DoubleArray(n)
        var fase = 0.0
        for (i in 0 until n) {
            fase += 2 * PI * hz / 16000
            onda[i] = if (tom) sin(fase) * .8 + sin(fase * 2) * .2 + (r.nextDouble() - .5) * .05 else r.nextDouble() * 2 - 1
        }
        var e = 0.0
        for (v in onda) e += v * v
        val rmsAtual = sqrt(e / n)
        val ganho = if (rmsAtual > 0) nivel / rmsAtual else 0.0
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = ((onda[i] * ganho).coerceIn(-1.0, 1.0) * 32767).roundToInt()
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Runs (level, ms) while she speaks; true if she was cut off. */
    private fun interrompe(som: List<Pair<Double, Int>>, tom: Boolean = true): Boolean {
        val vad = Vad().apply { reset() }
        for ((nivel, ms) in som) {
            var t = 0
            while (t < ms) {
                if (vad.feed(pedaco(nivel, tom = tom), speaking = true).isBargeIn) return true
                t += 100
            }
        }
        return false
    }

    private fun wav(nome: String): ByteArray {
        val all = javaClass.classLoader!!.getResourceAsStream("voice/$nome")!!.readBytes()
        return all.copyOfRange(44, all.size)
    }

    private fun chunks(pcm: ByteArray, size: Int = 2048): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < pcm.size) {
            out.add(pcm.copyOfRange(i, minOf(i + size, pcm.size)))
            i += size
        }
        return out
    }

    private fun silencio(ms: Int = 2000) = ByteArray(16000 * 2 * ms / 1000)

    // ------------------------------------------------------------------ barge_in_test.dart
    @Test fun passarinhoNaoACala() = assertFalse(interrompe(listOf(.0005 to 500, .05 to 300, .0005 to 1000), tom = false))

    @Test fun doisChilreiosTambemNao() =
        assertFalse(interrompe(listOf(.0005 to 300, .05 to 250, .0005 to 400, .05 to 250, .0005 to 600), tom = false))

    @Test fun televisaoNaSalaNaoACala() = assertFalse(interrompe(listOf(.035 to 4000)))

    @Test fun donoAFalarCalaA() = assertTrue(interrompe(listOf(.0005 to 200, .12 to 900)))

    @Test fun donoComPausasEntreSilabasCalaA() =
        assertTrue(interrompe(listOf(.12 to 300, .001 to 100, .12 to 300, .001 to 100, .12 to 300)))

    @Test fun pausaLongaDesiste() = assertFalse(interrompe(listOf(.12 to 100, .0005 to 1000, .12 to 100)))

    @Test fun palavraCurtaDeVozJaACala() = assertTrue(interrompe(listOf(.0005 to 200, .12 to 300)))

    @Test fun aviaoNaoACala() = assertFalse(interrompe(listOf(.0005 to 300, .18 to 5000, .0005 to 500), tom = false))

    @Test fun secadorCamiaoObraNaoACalam() = assertFalse(interrompe(listOf(.25 to 8000), tom = false))

    @Test fun shhhNaoACalaDePropositoSoVoz() = assertFalse(interrompe(listOf(.0005 to 200, .18 to 900), tom = false))

    @Test fun silencioAbsolutoNuncaACala() = assertFalse(interrompe(listOf(0.0 to 5000)))

    @Test fun escreverNoTecladoNaoACala() {
        val teclas = ArrayList<Pair<Double, Int>>()
        repeat(30) { teclas.add(.18 to 40); teclas.add(.0008 to 160) }
        assertFalse(interrompe(teclas, tom = false))
    }

    @Test fun escreverDepressaTambemNao() {
        val teclas = ArrayList<Pair<Double, Int>>()
        repeat(40) { teclas.add(.2 to 60); teclas.add(.001 to 60) }
        assertFalse(interrompe(teclas, tom = false))
    }

    @Test fun baterNaMesaNaoACala() = assertFalse(interrompe(listOf(.0008 to 300, .3 to 80, .0008 to 2000), tom = false))

    @Test fun aPropriaVozDelaNoAltifalanteNaoACala() = assertFalse(interrompe(listOf(.05 to 8000)))

    @Test fun oEcoUmPoucoMaisAltoTambemNao() = assertFalse(interrompe(listOf(.07 to 6000)))

    @Test fun comMuitoEcoEPrecisoFalarMaisAltoMasDa() {
        val vad = Vad().apply { reset() }
        var t = 0
        while (t < 2000) { vad.feed(pedaco(.05), speaking = true); t += 100 }
        var cortou = false
        t = 0
        while (t < 900) { if (vad.feed(pedaco(.25), speaking = true).isBargeIn) cortou = true; t += 100 }
        assertTrue(cortou)
    }

    // ------------------------------------------------------------------ ambiente_test.dart
    @Test fun numCafeBarulhentoContinuaAOuvir() {
        val vad = Vad().apply { reset() }
        var t = 0
        while (t < 2000) { vad.feed(pedaco(.055, tom = false, seed = 5)); t += 100 }
        var ouviu = false
        t = 0
        while (t < 1500) { if (vad.feed(pedaco(.15, seed = 5)).isCapturing) ouviu = true; t += 100 }
        assertTrue("ficou surda com o barulho a volta", ouviu)
    }

    @Test fun fasquiaDeOuvirNuncaPassaDoTeto() = assertTrue(Vad.tetoOuvir <= 0.12)

    @Test fun chaoDeInterromperEntreEcoEVoz() {
        assertTrue(Vad.bargeFloor > 0.06)
        assertTrue(Vad.bargeFloor < 0.12)
    }

    // ------------------------------------------------------------------ silencio_test.dart
    private fun corre(ficheiro: String): Pair<Int, Int> {
        val vad = Vad().apply { reset() }
        var fechos = 0
        var falaMs = 0
        for (c in chunks(wav(ficheiro)) + chunks(silencio())) {
            val r = vad.feed(c)
            if (r.isDone) { fechos++; falaMs = r.spokeMs; vad.newTurn() }
        }
        return fechos to falaMs
    }

    @Test fun fraseComPausasNaoECortadaEmPedacos() {
        val (fechos, falaMs) = corre("pausas.wav")
        assertEquals("a frase foi partida em $fechos pedacos", 1, fechos)
        assertTrue("so apanhou $falaMs ms de fala", falaMs > 2000)
    }

    @Test fun fraseCurtaFechaUmaVez() {
        val (fechos, falaMs) = corre("curta.wav")
        assertEquals(1, fechos)
        assertTrue(falaMs >= Vad.minSpeechMs)
    }

    @Test fun silencioDeFechoNaConstante() = assertTrue(Vad.quietToCloseMs in 600..900)

    // ------------------------------------------------------------------ vad_test.dart
    @Test fun fraseFaladaEReconhecidaEEnviada() {
        val vad = Vad()
        var end: VadStep? = null
        for (c in chunks(wav("fala.wav")) + chunks(silencio())) {
            val r = vad.feed(c)
            if (r.isDone) { end = r; break }
        }
        assertNotNull("nunca fechou a frase", end)
        assertTrue("a frase foi descartada como too short", end!!.enough)
        assertTrue(end.spokeMs > Vad.minSpeechMs)
    }

    @Test fun soRuidoDeFundoNaoEEnviado() {
        val vad = Vad()
        val noise = ByteArray(2048)
        for (i in noise.indices step 2) { noise[i] = 12; noise[i + 1] = 0 }
        var end: VadStep? = null
        for (k in 0 until 200) { val r = vad.feed(noise); if (r.isDone) { end = r; break } }
        assertFalse(end?.enough ?: false)
    }

    @Test fun pausaCurtaNoMeioNaoFecha() {
        val vad = Vad()
        val fala = chunks(wav("fala.wav"))
        val pausa = chunks(silencio(500))
        val all = fala.take(20) + pausa + fala.drop(20)
        var closedEarly = false
        for (c in all.take(20 + pausa.size + 5)) { if (vad.feed(c).isDone) { closedEarly = true; break } }
        assertFalse("cortava a meio da frase", closedEarly)
    }

    // ------------------------------------------------------------------ voz_vs_ruido_test.dart
    private fun tomMaximo(ficheiro: String): Double =
        chunks(wav(ficheiro)).filter { Vad.rmsOf(it) > 0.02 }.maxOfOrNull { Vad.periodicidade(it) } ?: 0.0

    private fun cala(ficheiro: String): Boolean {
        val vad = Vad().apply { reset() }
        for (c in chunks(wav(ficheiro))) if (vad.feed(c, speaking = true).isBargeIn) return true
        return false
    }

    @Test fun vozTemTomTecladoNao() {
        for (v in listOf("para.wav", "espera.wav", "curta.wav", "pausas.wav")) {
            assertTrue("$v deu tom ${tomMaximo(v)}", tomMaximo(v) > Vad.bargeVozMin)
        }
        assertTrue("as teclas deram tom ${tomMaximo("teclado.wav")}", tomMaximo("teclado.wav") < Vad.bargeVozMin)
    }

    @Test fun palavraCurtaParaCalaA() = assertTrue(cala("para.wav"))

    @Test fun esperaTambemACala() = assertTrue(cala("espera.wav"))

    @Test fun digitarNaoACala() = assertFalse(cala("teclado.wav"))

    // ------------------------------------------------------------------ whisper (contract)
    @Test fun sussurroEPicoBaixoETomBaixo() {
        val vad = Vad().apply { reset() }
        repeat(3) { vad.feed(pedaco(.001)) }
        repeat(10) { vad.feed(pedaco(.06, tom = false)) }
        assertTrue(vad.foiSussurro)
        val alto = Vad().apply { reset() }
        repeat(3) { alto.feed(pedaco(.001)) }
        repeat(10) { alto.feed(pedaco(.15)) }
        assertFalse(alto.foiSussurro)
    }
}

class VoiceApiTest {
    @Test fun appContextJsonKeepsTwentyItems() {
        val j = OryksaAppContext("product", "Lavender candle", (0 until 30).map { "item $it" }).toJson()
        assertEquals("product", j.getString("screen"))
        assertEquals("Lavender candle", j.getString("title"))
        assertEquals(20, j.getJSONArray("items").length())
        assertEquals(0, OryksaAppContext().toJson().length())
    }

    @Test fun recordingIsA16kMonoPcm16Wav() {
        val w = OryksaVoiceController.wav(ByteArray(32000))
        val b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals(1.toShort(), b.getShort(22))
        assertEquals(16000, b.getInt(24))
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals(32000, b.getInt(40))
        assertEquals(44 + 32000, w.size)
    }

    @Test fun agentIdentityFromOryksa() {
        val a = OryksaAgent.fromJson(org.json.JSONObject("""{"name":"Sofia","avatar":"https://x/ai.jpg","voice":"v123","language":"pt","voice_replies":true}"""))
        assertEquals("Sofia", a.name)
        assertEquals("https://x/ai.jpg", a.photo)
        assertEquals("v123", a.voice)
        assertEquals("pt", a.language)
        assertTrue(a.voiceReplies)
    }
}
