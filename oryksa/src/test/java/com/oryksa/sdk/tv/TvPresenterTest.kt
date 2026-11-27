package com.oryksa.sdk.tv

import com.oryksa.sdk.OryksaException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A player that "plays" for [playMs] and remembers what it was given. */
class FakeTvPlayer(private val playMs: Long = 60) : OryksaTvAudioPlayer {
    val played = mutableListOf<File>()
    @Volatile var stops = 0
    override suspend fun play(file: File, onStart: (durationMs: Long?) -> Unit) {
        played.add(file)
        onStart(playMs)
        delay(playMs)
    }
    override fun stop() { stops++ }
    override fun release() {}
}

// The presenter and the phone voice on the SIMULATED backend: this proves the SDK logic (cache, subtitles, events),
// not the ORYKSA server.
class TvPresenterTest {
    private val dir: File = Files.createTempDirectory("oryksa-tv-test").toFile()
    private val tvs = mutableListOf<OryksaTv>()

    @After
    fun cleanUp() {
        tvs.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun mp3(text: String, locale: String) = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + text.toByteArray().take(8)

    private fun tv(backend: OryksaTvBackend, locale: String, player: OryksaTvAudioPlayer = FakeTvPlayer()): OryksaTv =
        OryksaTv(backend, locale, dir, player, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { tvs.add(it) }

    @Test
    fun speaksWithTheVoiceAndTheSameLineAgainIsCountedAgainAndPlaysTheKeptVoice() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3)
        val player = FakeTvPlayer()
        val tv = tv(fake, "pt-BR", player)
        val events = mutableListOf<String>()
        tv.presenter.onStart = { events.add("start") }
        tv.presenter.onEnd = { events.add("end") }
        val phases = mutableSetOf<OryksaPresenterPhase>()
        val watch = launch(Dispatchers.Default) { tv.presenter.state.collect { phases.add(it.phase) } }
        tv.presenter.say("Olá! Sou a ORYKSA.")
        assertEquals(1, fake.speakCalls)
        assertEquals(1, player.played.size)
        assertTrue(player.played.single().isFile)
        tv.presenter.say("Olá! Sou a ORYKSA.")
        assertEquals("each time she says it, it counts (owner, 26/11/2026)", 2, fake.speakCalls)
        assertEquals("the server is told the device has it, so it need not make the voice again", "sim-1", fake.speakRequests.last().cachedRev)
        assertEquals(2, player.played.size)
        assertEquals("the kept voice plays", player.played[0], player.played[1])
        watch.cancel()
        assertTrue(phases.contains(OryksaPresenterPhase.SPEAKING))
        assertEquals(listOf("start", "end", "start", "end"), events)
        assertFalse(tv.presenter.isBusy)
    }

    @Test
    fun aServerWithoutVoiceRevCountsTheLineSaidAgain() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3, fixedConfig = OryksaTvConfig(locale = "it-IT", fromServer = false))
        val tv = tv(fake, "it-IT")
        tv.presenter.say("Ciao! Sono ORYKSA.")
        tv.presenter.say("Ciao! Sono ORYKSA.")
        assertEquals(2, fake.speakCalls)
        assertEquals("the revision the voice came with", "sim-1", fake.speakRequests.last().cachedRev)
    }

    @Test
    fun aKeptLineTellsTheServerItsRevisionAndANewVoiceReplacesIt() = runBlocking {
        // 26/11/2026 the pt-BR voice changed on the server (99cb5c17c2ff -> 9870f9eea051) while apps may be open.
        val fake = OryksaTvFakeBackend(voice = ::mp3) // its voice is "sim-1"
        var serverRev = "old-rev"
        val backend = object : OryksaTvBackend by fake {
            override suspend fun config(locale: String) = OryksaTvConfig(locale = "pt-BR", voiceRev = serverRev)
        }
        val player = FakeTvPlayer()
        val tv = tv(backend, "pt-BR", player)
        // A line kept with the old voice.
        val line = "Olá! A voz mudou."
        val h = java.security.MessageDigest.getInstance("SHA-1").digest("old-rev|pt-BR|0|$line".toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "$h.mp3").writeBytes(byteArrayOf(9, 9, 9))
        tv.presenter.say(line)
        assertEquals("counted, and the server is told which voice the device has", "old-rev", fake.speakRequests.single().cachedRev)

        // The server answered with the new voice: the configuration is read again (after its 10 minutes; 0 here).
        serverRev = "sim-1"
        tv.configTtlMs = 0
        tv.presenter.say(line)
        assertEquals(2, fake.speakCalls)
        assertEquals("now kept under the new revision", "sim-1", fake.speakRequests.last().cachedRev)
    }

    @Test
    fun aVoiceThatDoesNotMatchTheConfigurationMakesItReadAgain() = runBlocking {
        var reads = 0
        var serverRev = "old-rev"
        val fake = OryksaTvFakeBackend(voice = ::mp3) // its voices come with revision "sim-1"
        val backend = object : OryksaTvBackend by fake {
            override suspend fun config(locale: String): OryksaTvConfig { reads++; return OryksaTvConfig(locale = "pt-BR", voiceRev = serverRev) }
        }
        val tv = tv(backend, "pt-BR")
        tv.presenter.say("Uma frase.")
        assertEquals(1, reads)
        // The voice came as "sim-1" but the configuration said "old-rev": read it again on the next line.
        serverRev = "sim-1"
        tv.presenter.say("Uma frase.")
        assertEquals(2, reads)
        assertEquals("counted each time", 2, fake.speakCalls)
        assertEquals("then kept under the new revision", "sim-1", fake.speakRequests.last().cachedRev)
    }

    @Test
    fun theConfigurationIsKeptTenMinutes() = runBlocking {
        var reads = 0
        val fake = OryksaTvFakeBackend()
        val backend = object : OryksaTvBackend by fake {
            override suspend fun config(locale: String): OryksaTvConfig { reads++; return OryksaTvConfig(locale = locale) }
        }
        val tv = tv(backend, "pt-BR")
        tv.loadConfig(); tv.loadConfig()
        assertEquals(1, reads)
        tv.configTtlMs = 0
        tv.loadConfig()
        assertEquals(2, reads)
    }

    @Test
    fun withoutVoiceTheSubtitlesOneByOne() = runBlocking {
        val fake = OryksaTvFakeBackend()
        val player = FakeTvPlayer()
        val tv = tv(fake, "pt-PT", player)
        tv.presenter.readingTimeMs = { 30 }
        val lines = mutableListOf<String>()
        val watch = launch(Dispatchers.Default) {
            tv.presenter.state.collect { if (it.line.isNotEmpty() && lines.lastOrNull() != it.line) lines.add(it.line) }
        }
        tv.presenter.say("Bem-vindo. Vamos começar.")
        delay(50)
        watch.cancel()
        assertEquals(1, fake.speakCalls)
        assertEquals(0, player.played.size)
        assertEquals(listOf("Bem-vindo.", "Vamos começar."), lines)
    }

    /** A server that answers the line without counting it (no `X-ORYKSA-Interactions-Spent`), or refuses it (402). */
    private class NotCounting(val inner: OryksaTvFakeBackend, private val refuse: Boolean = false) : OryksaTvBackend by inner {
        override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?): OryksaTvSpeech? {
            val s = inner.speak(text, locale, whisper, voice, cachedRev)!!
            if (refuse) throw OryksaException(402, "interactions_exhausted", "No interactions left.")
            return OryksaTvSpeech(s.bytes, s.locale, s.voiceRev, null)
        }
    }

    @Test
    fun todaysServerWithoutTheCachedHintSendsTheVoiceAgainWhichPlaysAndCounts() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3)
        val oldServer = object : OryksaTvBackend by fake {
            override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?) =
                fake.speak(text, locale, whisper, voice, null)
        }
        val player = FakeTvPlayer()
        val tv = tv(oldServer, "pt-BR", player)
        tv.presenter.say("Olá de novo.")
        tv.presenter.say("Olá de novo.")
        assertEquals(2, fake.speakCalls)
        assertEquals(2, player.played.size)
    }

    @Test
    fun voiceFalseShowsTheSubtitlesOnlyAtReadingSpeedWithNoSoundAndCountsOne() = runBlocking {
        val server = OryksaTvFakeBackend(voice = ::mp3)
        val player = FakeTvPlayer()
        val tv = tv(server, "pt-BR", player)
        tv.presenter.readingTimeMs = { 200 }
        val events = mutableListOf<String>()
        tv.presenter.onStart = { events.add("start") }
        tv.presenter.onEnd = { events.add("end") }
        val lines = mutableListOf<String>()
        val phases = mutableSetOf<OryksaPresenterPhase>()
        val watch = launch(Dispatchers.Default) {
            tv.presenter.state.collect {
                phases.add(it.phase)
                if (it.line.isNotEmpty() && lines.lastOrNull() != it.line) lines.add(it.line)
            }
        }
        val started = System.nanoTime()
        tv.presenter.say("Encontramos alguém para você. Boa sorte!", voice = false)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        delay(50)
        watch.cancel()
        assertEquals("counted 1 by the server, like a spoken line (owner, 26/11/2026)", 1, server.speakCalls)
        assertFalse("no voice asked for", server.speakRequests.single().voice)
        assertEquals(0, player.played.size)
        assertEquals(listOf("Encontramos alguém para você.", "Boa sorte!"), lines)
        assertTrue("each line stays its reading time ($elapsedMs ms)", elapsedMs >= 400)
        assertTrue(phases.containsAll(listOf(OryksaPresenterPhase.SPEAKING, OryksaPresenterPhase.IDLE)))
        assertEquals(listOf("start", "end"), events)
        assertFalse(tv.presenter.isBusy)
        assertEquals("", tv.presenter.state.value.line)
    }

    /** One line on the real client against a local server; [tts] answers `POST /client/tts` (the rest gets 500). */
    private class RealRun(val ttsBodies: List<JSONObject>, val lines: List<String>, val errors: List<Throwable>)

    private suspend fun realRun(text: String, voice: Boolean, baseUrl: String? = null, tts: (TvRequest) -> TvAnswer): RealRun {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val server = TvServer { r ->
            if (!r.path.endsWith("/client/tts")) TvAnswer.json(500, "{\"error\":{\"code\":\"down\",\"message\":\"down\"}}")
            else { bodies.add(JSONObject(r.body)); tts(r) }
        }
        return server.use { s ->
            val client = com.oryksa.sdk.OryksaClient(token = "oryk_cs_test", baseUrl = baseUrl ?: s.base, platform = "fire_tv", timeoutMs = 3_000)
            val tv = tv(OryksaTvClientBackend(client), "es-ES")
            tv.presenter.readingTimeMs = { 10 }
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            tv.presenter.onError = { errors.add(it) }
            val watch = CoroutineScope(Dispatchers.Default).launch {
                tv.presenter.state.collect { if (it.line.isNotEmpty() && lines.lastOrNull() != it.line) lines.add(it.line) }
            }
            delay(20)
            tv.presenter.say(text, voice = voice)
            delay(30)
            watch.cancel()
            RealRun(bodies.toList(), lines.toList(), errors.toList())
        }
    }

    @Test
    fun voiceFalseOnTheRealClientAsksTheServerWithVoiceFalseCountedTheSubtitlesShow() = runBlocking {
        val r = realRun("Hola. Hoy hablamos de viajes.", voice = false) {
            TvAnswer(204, ByteArray(0), mapOf("X-ORYKSA-Interactions-Spent" to "1", "X-ORYKSA-Locale" to "es-ES"))
        }
        assertEquals(1, r.ttsBodies.size)
        assertEquals(false, r.ttsBodies.single().getBoolean("voice"))
        assertEquals("presenter", r.ttsBodies.single().getString("purpose"))
        assertEquals("Hola.", r.lines.first())
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun aLineTheServerDidNotCountIsNotShown() = runBlocking {
        val r = realRun("Hola. Hoy hablamos de viajes.", voice = false) { TvAnswer(204) }
        assertEquals(1, r.ttsBodies.size)
        assertTrue(r.lines.isEmpty())
        assertEquals("not_counted", (r.errors.single() as OryksaException).code)
    }

    @Test
    fun noInteractionsLeftNothingIsShownAndTheNextPiecesAreNotAsked() = runBlocking {
        val long = (0 until 30).joinToString(" ") { "Frase número $it de un texto largo para la presentadora." }
        val r = realRun(long, voice = true) {
            TvAnswer.json(402, "{\"error\":{\"code\":\"interactions_exhausted\",\"message\":\"No interactions left.\"}}")
        }
        assertEquals("she stops at the first piece not counted", 1, r.ttsBodies.size)
        assertTrue(r.lines.isEmpty())
        assertEquals("interactions_exhausted", (r.errors.single() as OryksaException).code)
    }

    @Test
    fun aNetworkErrorShowsNothingBeforeTheSubtitlesShowedWithoutBeingCounted() = runBlocking {
        val r = realRun("Hola.", voice = true, baseUrl = "http://127.0.0.1:1/v1") { TvAnswer(500) }
        assertTrue(r.lines.isEmpty())
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun aSpokenLineTheServerDidNotCountIsNotShownAndPlaysNoSound() = runBlocking {
        val fake = NotCounting(OryksaTvFakeBackend(voice = ::mp3))
        val player = FakeTvPlayer()
        val tv = tv(fake, "pt-BR", player)
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watch = launch(Dispatchers.Default) { tv.presenter.state.collect { if (it.line.isNotEmpty()) lines.add(it.line) } }
        tv.presenter.say("Olá.")
        delay(30)
        watch.cancel()
        assertEquals(1, fake.inner.speakCalls)
        assertEquals(0, player.played.size)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aRepeatedLineTheServerRefusesDoesNotPlayTheKeptVoice() = runBlocking {
        val first = tv(OryksaTvFakeBackend(voice = ::mp3), "pt-BR")
        first.presenter.say("Boa noite.") // now kept on the device
        val refusing = NotCounting(OryksaTvFakeBackend(voice = ::mp3), refuse = true)
        val player = FakeTvPlayer()
        val tv = tv(refusing, "pt-BR", player)
        tv.presenter.readingTimeMs = { 10 }
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        tv.presenter.onError = { errors.add(it) }
        tv.presenter.say("Boa noite.")
        assertEquals(1, refusing.inner.speakCalls)
        assertEquals("sim-1", refusing.inner.speakRequests.single().cachedRev)
        assertEquals("before, a kept line played with no request at all", 0, player.played.size)
        assertEquals(402, (errors.single() as OryksaException).status)
    }

    @Test
    fun okAndBackStopATextOnlyLine() = runBlocking {
        val tv = tv(OryksaTvFakeBackend(), "pt-PT")
        tv.presenter.readingTimeMs = { 30_000 }
        val saying = launch(Dispatchers.Default) { tv.presenter.say("Uma linha só para ler.", voice = false) }
        withTimeout(3_000) { while (tv.presenter.state.value.phase != OryksaPresenterPhase.SPEAKING) delay(5) }
        assertEquals("Uma linha só para ler.", tv.presenter.state.value.line)
        tv.presenter.stop()
        withTimeout(2_000) { saying.join() }
        assertFalse(tv.presenter.isBusy)
        assertEquals("", tv.presenter.state.value.line)
    }

    @Test
    fun aSpokenLineGivenDuringATextOnlyOneReplacesIt() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3)
        val player = FakeTvPlayer()
        val tv = tv(fake, "pt-BR", player)
        tv.presenter.readingTimeMs = { 30_000 }
        val first = launch(Dispatchers.Default) { tv.presenter.say("Linha para ler.", voice = false) }
        withTimeout(3_000) { while (tv.presenter.state.value.phase != OryksaPresenterPhase.SPEAKING) delay(5) }
        tv.presenter.say("Agora com voz.")
        withTimeout(2_000) { first.join() }
        assertEquals("the text-only line counted 1, the spoken one 1", 2, fake.speakCalls)
        assertEquals(1, player.played.size)
        assertFalse(tv.presenter.isBusy)
    }

    @Test
    fun sayGeneratedWithVoiceFalseCountsTheAiAnswerAndTheLineWithNoSound() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3)
        val player = FakeTvPlayer()
        val tv = tv(fake, "pt-BR", player)
        tv.presenter.readingTimeMs = { 10 }
        val text = tv.presenter.sayGenerated("Say goodbye.", "Ana", voice = false)
        assertTrue(text.isNotEmpty())
        assertEquals(1, fake.aiCalls)
        assertEquals(1, fake.speakCalls)
        assertFalse(fake.speakRequests.single().voice)
        assertEquals(0, player.played.size)
    }

    @Test
    fun stopEndsHerNow() = runBlocking {
        val tv = tv(OryksaTvFakeBackend(voice = ::mp3), "pt-PT", FakeTvPlayer(30_000))
        val saying = launch(Dispatchers.Default) { tv.presenter.say("Uma fala muito longa.") }
        withTimeout(3_000) { while (tv.presenter.state.value.phase != OryksaPresenterPhase.SPEAKING) delay(5) }
        tv.presenter.stop()
        withTimeout(2_000) { saying.join() }
        assertFalse(tv.presenter.isBusy)
    }

    @Test
    fun aLongTextIsSentInPieces() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3, fixedConfig = OryksaTvConfig(locale = "pt-PT", voiceRev = "sim-limit", rules = OryksaTvRules(presenterMaxChars = 60)))
        val tv = tv(fake, "pt-PT")
        tv.presenter.say((0 until 4).joinToString(" ") { "Esta é a frase $it da apresentadora." })
        assertTrue(fake.speakCalls > 1)
    }

    @Test
    fun sayGeneratedAsksTheAiThenSpeaks() = runBlocking {
        val fake = OryksaTvFakeBackend(voice = ::mp3)
        val tv = tv(fake, "es-MX")
        val text = tv.presenter.sayGenerated("Announce the next date.", "Ana and Rui")
        assertEquals(1, fake.aiCalls)
        assertTrue(text.startsWith("(simulated)"))
        assertEquals(1, fake.speakCalls)
    }

    @Test
    fun phoneVoiceQrConnectsTextArrivesStopEnds() = runBlocking {
        val fake = OryksaTvFakeBackend()
        val tv = tv(fake, "pt-PT")
        val pv = tv.phoneVoice
        pv.pollWaitSec = 1
        pv.retryPauseMs = 10
        val heard = mutableListOf<String>()
        pv.onText = { heard.add(it) }
        pv.start()
        withTimeout(3_000) { while (pv.state.value.phase != OryksaPhoneVoicePhase.WAITING) delay(5) }
        assertEquals("K7Q2MZ", pv.state.value.pairing!!.code)
        fake.phoneConnects()
        withTimeout(3_000) { while (pv.state.value.phase != OryksaPhoneVoicePhase.PAIRED) delay(5) }
        fake.phoneSays("Como funciona o encontro?")
        withTimeout(3_000) { while (heard.isEmpty()) delay(5) }
        assertEquals(listOf("Como funciona o encontro?"), heard)
        assertEquals("Como funciona o encontro?", pv.state.value.lastText)
        fake.phoneLeaves()
        withTimeout(3_000) { while (pv.state.value.phase != OryksaPhoneVoicePhase.PHONE_LEFT) delay(5) }
        pv.stop()
        assertEquals(OryksaPhoneVoicePhase.IDLE, pv.state.value.phase)
        withTimeout(3_000) { while (fake.unpaired.isEmpty()) delay(5) }
        assertEquals(listOf(OryksaTvFakeBackend.pairIdOf(1)), fake.unpaired.toList())
    }

    @Test
    fun anExpiredCodeEndsTheWaitAndRestartGivesANewCode() = runTest {
        // A controlled clock: the TV pieces run on this test's scheduler, so the long-poll, the code's expiry and
        // every wait here are virtual time, one coroutine at a time, never the machine's clock or threads. On the
        // real threads this test failed about 1 run in 3 (27/11/2026).
        val fake = OryksaTvFakeBackend()
        val tv = OryksaTv(fake, "pt-PT", dir, FakeTvPlayer(), CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
            .also { tvs.add(it) }
        val pv = tv.phoneVoice
        pv.pollWaitSec = 1
        pv.start()
        runCurrent()
        assertEquals(OryksaPhoneVoicePhase.WAITING, pv.state.value.phase)
        assertEquals("K7Q2MZ", pv.state.value.pairing!!.code)
        fake.expire()
        runCurrent()
        assertEquals(OryksaPhoneVoicePhase.EXPIRED, pv.state.value.phase)
        assertFalse(pv.state.value.isActive)
        pv.restart()
        runCurrent()
        assertEquals(OryksaPhoneVoicePhase.WAITING, pv.state.value.phase)
        assertEquals("H4PRWX", pv.state.value.pairing!!.code)
        assertEquals(listOf(OryksaTvFakeBackend.pairIdOf(1)), fake.unpaired.toList())
        // Five long-polls later the new code is still waiting: the end of the old pairing did not close it.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(OryksaPhoneVoicePhase.WAITING, pv.state.value.phase)
        assertEquals("H4PRWX", pv.state.value.pairing!!.code)
        pv.stop()
        runCurrent()
        assertEquals(listOf(OryksaTvFakeBackend.pairIdOf(1), OryksaTvFakeBackend.pairIdOf(2)), fake.unpaired.toList())
    }
}
