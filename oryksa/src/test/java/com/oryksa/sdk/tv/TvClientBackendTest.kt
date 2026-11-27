package com.oryksa.sdk.tv

import com.oryksa.sdk.OryksaClient
import com.oryksa.sdk.OryksaException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

// The real backend against the contract of sdk-tv/DESENHO.md section 9, on a local HTTP server. This proves the
// requests the SDK sends and how it reads the answers; it does NOT prove the ORYKSA server.
class TvClientBackendTest {
    private fun client(base: String, platform: String = "fire_tv", timeoutMs: Int = 60_000) =
        OryksaClient(token = "oryk_cs_test", baseUrl = base, platform = platform, timeoutMs = timeoutMs)

    @Test
    fun presenterVoiceSendsLocalePurposeAndPlatformAndReadsTheHeaders() = runBlocking {
        TvServer { TvAnswer(200, byteArrayOf(1, 2, 3), mapOf("Content-Type" to "audio/mpeg", "X-ORYKSA-Locale" to "es-419",
            "X-ORYKSA-Voice-Rev" to "a1b2c3d4e5f6", "X-ORYKSA-Interactions-Spent" to "1")) }.use { s ->
            val sp = OryksaTvClientBackend(client(s.base)).speak("Hola", "es_MX")!!
            val r = s.seen.single()
            assertEquals("POST", r.method)
            assertEquals("/v1/client/tts", r.path)
            val b = JSONObject(r.body)
            assertEquals("Hola", b.getString("text"))
            assertEquals("es-MX", b.getString("locale"))
            assertEquals("presenter", b.getString("purpose"))
            assertEquals("fire_tv", b.getString("platform"))
            assertArrayEquals(byteArrayOf(1, 2, 3), sp.bytes)
            assertEquals("es-419", sp.locale)
            assertEquals("a1b2c3d4e5f6", sp.voiceRev)
            assertEquals(1, sp.interactionsSpent)
            assertFalse("a normal line asks for the voice", b.has("voice"))
            assertFalse(b.has("cached"))
        }
    }

    @Test
    fun aSubtitlesOnlyLineSendsVoiceFalseAKeptLineSendsCachedAndItsRevisionBothReadA204() = runBlocking {
        TvServer { TvAnswer(204, ByteArray(0), mapOf("X-ORYKSA-Interactions-Spent" to "1", "X-ORYKSA-Voice-Rev" to "r7")) }.use { s ->
            val b = OryksaTvClientBackend(client(s.base))
            val textOnly = b.speak("Olá", "pt-PT", voice = false)!!
            val kept = b.speak("Olá", "pt-PT", cachedRev = "r7")!!
            val first = JSONObject(s.seen[0].body)
            assertEquals(false, first.getBoolean("voice"))
            assertFalse(first.has("cached"))
            val second = JSONObject(s.seen[1].body)
            assertEquals(true, second.getBoolean("cached"))
            assertEquals("r7", second.getString("voice_rev"))
            assertFalse(second.has("voice"))
            assertEquals(0, textOnly.bytes.size)
            assertEquals(1, textOnly.interactionsSpent)
            assertEquals(0, kept.bytes.size)
            assertEquals(1, kept.interactionsSpent)
        }
    }

    @Test
    fun voiceUnavailableIsNullOtherErrorsThrow() = runBlocking {
        var answer = TvAnswer.json(503, """{"error":{"code":"voice_unavailable","message":"no voice"}}""")
        TvServer { answer }.use { s ->
            val b = OryksaTvClientBackend(client(s.base))
            assertNull(b.speak("Olá", "pt-PT"))
            answer = TvAnswer.json(400, """{"error":{"code":"text_too_long","message":"too long"}}""")
            try {
                b.speak("Olá", "pt-PT")
                fail("expected text_too_long")
            } catch (e: OryksaException) {
                assertEquals("text_too_long", e.code)
            }
        }
    }

    @Test
    fun configurationAsksTheLocale() = runBlocking {
        TvServer { TvAnswer.json(200, """{"name":"ORYKSA","tv":{"locale":"pt-BR","voice_rev":"v1","texts":{"close":"Fechar"}}}""") }.use { s ->
            val c = OryksaTvClientBackend(client(s.base)).config("pt_BR")
            val r = s.seen.single()
            assertEquals("GET", r.method)
            assertEquals("/v1/client/agent", r.path)
            assertEquals("locale=pt-BR", r.query)
            assertEquals("v1", c.voiceRev)
            assertEquals("Fechar", c.texts.get("close"))
        }
    }

    @Test
    fun chatFromThePanelSendsPlatformLangAndVoice() = runBlocking {
        TvServer { TvAnswer.json(200, """{"status":"replied","reply":"Olá!","speech":"Olá!"}""") }.use { s ->
            val r = OryksaTvClientBackend(client(s.base)).chat("oi", "pt-BR", voice = true)
            val b = JSONObject(s.seen.single().body)
            assertEquals("fire_tv", b.getString("platform"))
            assertEquals("br", b.getString("lang"))
            assertEquals(true, b.getBoolean("voice"))
            assertEquals("Olá!", r.reply)
        }
    }

    @Test
    fun pairingLongPollAndClose() = runBlocking {
        TvServer { r ->
            when (r.method) {
                "POST" -> TvAnswer.json(201, """{"pair_id":"tvp_9f2c41d0","code":"K7Q2MZ","url":"https://oryksa.com/tv?c=K7Q2MZ",
                    "short_url":"oryksa.com/tv","qr_png_url":"https://x/q.png","expires_in":300}""")
                "GET" -> TvAnswer.json(200, """{"state":"paired","phone":true,"next":3,"events":[{"seq":3,"type":"text","text":"Olá","lang":"pt-PT","final":true}]}""")
                else -> TvAnswer(204)
            }
        }.use { s ->
            val b = OryksaTvClientBackend(client(s.base, timeoutMs = 20_000))
            val p = b.pair("pt_PT")
            assertEquals("/v1/client/tv/pair", s.seen[0].path)
            val body = JSONObject(s.seen[0].body)
            assertEquals("pt-PT", body.getString("locale"))
            assertEquals("fire_tv", body.getString("platform"))
            val page = b.phoneEvents(p.pairId, after = 2, waitSec = 25)
            assertEquals("/v1/client/tv/pair/tvp_9f2c41d0/events", s.seen[1].path)
            assertEquals("after=2&wait=15", s.seen[1].query)
            assertEquals("Olá", page.events.single().text)
            b.unpair(p.pairId)
            assertEquals("DELETE", s.seen[2].method)
            assertEquals("/v1/client/tv/pair/tvp_9f2c41d0", s.seen[2].path)
        }
    }

    @Test
    fun waitIsNeverOver30Seconds() = runBlocking {
        TvServer { TvAnswer.json(200, """{"state":"waiting","next":0,"events":[]}""") }.use { s ->
            OryksaTvClientBackend(client(s.base, timeoutMs = 120_000)).phoneEvents("tvp_x", 0, 90)
            assertEquals("after=0&wait=30", s.seen.single().query)
        }
    }

    @Test
    fun publicSessionSendsPlatformAndAppIdOnlyWhenGiven() = runBlocking {
        TvServer { r ->
            if (r.path.endsWith("/public/sessions")) TvAnswer.json(201, """{"client_token":"oryk_cs_new"}""")
            else TvAnswer.json(200, """{"status":"replied","reply":"ok"}""")
        }.use { s ->
            OryksaClient.public("oryk_pub_x", "c1", platform = "fire_tv", appId = "tv.blinddating.app", baseUrl = s.base).send("olá")
            val first = JSONObject(s.seen[0].body)
            assertEquals("oryk_pub_x", first.getString("widget_token"))
            assertEquals("fire_tv", first.getString("platform"))
            assertEquals("tv.blinddating.app", first.getString("app_id"))
            assertEquals("Bearer oryk_cs_new", s.seen[1].headers["authorization"])
            assertEquals("fire_tv", JSONObject(s.seen[1].body).getString("platform"))

            OryksaClient.public("oryk_pub_x", "c2", baseUrl = s.base).send("olá")
            val phone = JSONObject(s.seen[2].body)
            assertEquals("android", phone.getString("platform"))
            assertFalse(phone.has("app_id"))
        }
    }
}
