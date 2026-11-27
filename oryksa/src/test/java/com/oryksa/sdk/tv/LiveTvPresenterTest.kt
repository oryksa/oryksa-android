package com.oryksa.sdk.tv

import com.oryksa.sdk.OryksaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

// LIVE test against the real ORYKSA server. It USES ORYKSA INTERACTIONS of the account of the token (3 lines, charged
// by the ORYKSA interaction cost table: a spoken line at the voice price, a subtitles-only line at the text price), so it is
// opt-in: without ORYKSA_LIVE=1 it is skipped and `./gradlew test` (and CI) never calls the real server.
// Run it only with the token of a TEST account, given by the environment, never written in the repository:
//   ORYKSA_LIVE=1 ORYKSA_TV_TOKEN=<public chat token> ./gradlew --no-daemon :oryksa:testDebugUnitTest --tests '*LiveTvPresenterTest*'
//   (or ORYKSA_TV_TOKEN_FILE=<file with the public chat token> instead of ORYKSA_TV_TOKEN)
// Optional ORYKSA_PROOF_OUT=<log file>. The token is never printed; the log has the fields sent and the answers only.
class LiveTvPresenterTest {
    /** The token of the TEST account from the environment: ORYKSA_TV_TOKEN, or the file named by ORYKSA_TV_TOKEN_FILE. */
    private fun liveToken(): String? {
        System.getenv("ORYKSA_TV_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val f = System.getenv("ORYKSA_TV_TOKEN_FILE")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return null
        if (!f.isFile) return null
        return f.readText().trim().takeIf { it.isNotEmpty() }
    }

    /** The interactions the server charged for a logged line (`spent=N`), 0 when it sent none. */
    private fun spentOf(line: String): Int = Regex(""" spent=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    @Test
    fun liveNewLineKeptLineAndSubtitlesOnlyAreEachCharged() = runBlocking {
        Assume.assumeTrue("live test: uses interactions; set ORYKSA_LIVE=1 and the token of a TEST account",
            System.getenv("ORYKSA_LIVE") == "1")
        val token = liveToken() ?: throw AssertionError("ORYKSA_LIVE=1 needs ORYKSA_TV_TOKEN or ORYKSA_TV_TOKEN_FILE (a TEST account).")
        val out = System.getenv("ORYKSA_PROOF_OUT")?.takeIf { it.isNotEmpty() }
        val client = OryksaClient.public(chatToken = token, conversationId = "tvlive" + System.nanoTime(), platform = "fire_tv", appId = "com.oryksa.example.tv")
        val real = OryksaTvClientBackend(client)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backend = object : OryksaTvBackend by real {
            override suspend fun speak(text: String, locale: String, whisper: Boolean, voice: Boolean, cachedRev: String?): OryksaTvSpeech? {
                val s = real.speak(text, locale, whisper, voice, cachedRev)
                log.add("${Instant.now()} POST /v1/client/tts sent voice=$voice cached_rev=${cachedRev ?: "-"} locale=$locale text=\"$text\" -> " +
                    "bytes=${s?.bytes?.size} spent=${s?.interactionsSpent} voice_rev=${s?.voiceRev} server_locale=${s?.locale}")
                return s
            }
        }
        val dir = Files.createTempDirectory("oryksa-tv-live").toFile()
        val player = FakeTvPlayer()
        val tv = OryksaTv(backend, "pt-BR", dir, player, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        tv.presenter.readingTimeMs = { 50 }
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        tv.presenter.onError = { errors.add(it.toString()) }
        val shown = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watch = launch(Dispatchers.Default) {
            tv.presenter.state.collect { if (it.line.isNotEmpty() && shown.lastOrNull() != it.line) shown.add(it.line) }
        }
        val stamp = Instant.now().toString().substring(11, 19)
        val line = "Prova ao vivo da cobrança do SDK Android de TV, às $stamp."
        val captions = "Só legendas no Android, sem som, às $stamp."
        tv.presenter.say(line) // 1: new
        tv.presenter.say(line) // 2: the device has it
        tv.presenter.say(captions, voice = false) // 3: subtitles only
        delay(100)
        watch.cancel()
        tv.close()
        dir.deleteRecursively()

        val summary = listOf("Android SDK (ramo tv) contra o servidor real, conta de TESTE, ${Instant.now()}") + log +
            listOf("player.played=${player.played.map { it.name }}", "shown=$shown", "errors=$errors",
                "spent (by the cost table, per line)=${log.map { spentOf(it) }}")
        if (out != null) File(out).writeText(summary.joinToString("\n") + "\n")

        assertEquals(3, log.size)
        assertTrue(log[0], log[0].contains("cached_rev=-") && !log[0].contains("bytes=0 "))
        assertTrue(log[1], log[1].contains("voice=true") && !log[1].contains("cached_rev=-") && log[1].contains("bytes=0 "))
        assertTrue(log[2], log[2].contains("voice=false") && log[2].contains("bytes=0 "))
        // Every line is charged; the number is the cost table's, never fixed here.
        for (l in log) assertTrue(l, spentOf(l) >= 1)
        assertEquals(2, player.played.size)
        assertEquals("the kept voice plays the second time", player.played[0], player.played[1])
        assertTrue(shown.toString(), shown.contains(captions))
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
