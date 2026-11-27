package com.oryksa.sdk.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// TV SDK (26/11/2026): the contract of sdk-tv/DESENHO.md section 9, as the live server answers it.
class TvModelsTest {
    @Test
    fun localesAreNormalisedAndTheChatKeepsItsOldCodes() {
        assertEquals("pt-BR", oryksaTvNormalizeLocale("pt_br"))
        assertEquals("es-419", oryksaTvNormalizeLocale("ES-419"))
        assertEquals("zh-Hant-TW", oryksaTvNormalizeLocale("zh_hant_tw"))
        assertEquals("", oryksaTvNormalizeLocale("  "))
        assertEquals("br", oryksaTvChatLang("pt-BR"))
        assertEquals("pt", oryksaTvChatLang("pt-PT"))
        assertEquals("es", oryksaTvChatLang("es-419"))
        assertEquals("fr", oryksaTvChatLang("fr-FR"))
        assertEquals("en-US", OryksaTvFakeBackend.resolveLocale("it-IT"))
        assertEquals("es-419", OryksaTvFakeBackend.resolveLocale("es-MX"))
        assertEquals("pt-PT", OryksaTvFakeBackend.resolveLocale("pt-AO"))
    }

    @Test
    fun readsTheWholeTvBlockOfTheLiveServer() {
        val c = OryksaTvConfig.fromAgentJson(JSONObject("""
            {"name":"ORYKSA","avatar":"https://x/a.png","tv":{"locale":"pt-BR","voice_rev":"99cb5c17c2ff",
             "presenter":{"name":"ORYKSA","avatar":"https://x/p.png"},
             "texts":{"pair_step_code":"Ou abra {url} e digite {code}","send":"Enviar"},
             "suggestions":["Como funciona?"," ","O que você pode fazer?"],
             "actions":[{"id":"phone_voice","kind":"phone_voice","label":"Falar pelo celular"},
                        {"id":"help","kind":"message","label":"Ajuda","message":"Preciso de ajuda"},
                        {"id":"bad","kind":"message","label":"Sem mensagem"},{"id":"new","kind":"teleport","label":"Novo"}],
             "rules":{"phone_voice":true,"tv_mic":"off","speak_replies":false,"presenter_max_chars":300}}}
        """.trimIndent()), "pt-BR")
        assertTrue(c.fromServer)
        assertEquals("pt-BR", c.locale)
        assertEquals("99cb5c17c2ff", c.voiceRev)
        assertEquals("https://x/p.png", c.presenterAvatar)
        assertEquals(listOf("Como funciona?", "O que você pode fazer?"), c.suggestions)
        assertEquals(listOf("phone_voice", "help"), c.actions.map { it.id })
        assertEquals(OryksaTvActionKind.PHONE_VOICE, c.actions[0].kind)
        assertEquals("off", c.rules.tvMic)
        assertFalse(c.rules.speakReplies)
        assertEquals(300, c.rules.presenterMaxChars)
        assertEquals("Ou abra oryksa.com/tv e digite K7Q2MZ", c.texts.get("pair_step_code", mapOf("url" to "oryksa.com/tv", "code" to "K7Q2MZ")))
        assertEquals("Enviar", c.texts.get("send"))
        assertEquals("Try again", c.texts.get("retry"))
    }

    @Test
    fun withoutTvBlockItStillWorksWithTheEnglishFallback() {
        val c = OryksaTvConfig.fromAgentJson(JSONObject("""{"name":"Ana","avatar":"https://x/a.png"}"""), "fr_ca")
        assertFalse(c.fromServer)
        assertEquals("fr-CA", c.locale)
        assertNull(c.voiceRev)
        assertEquals("Ana", c.presenterName)
        assertEquals("Talk on your phone", c.texts.get("pair_title"))
        assertEquals(600, c.rules.presenterMaxChars)
    }

    @Test
    fun pairingAndEventsAsTheLiveS3() {
        val p = OryksaPairing.fromJson(JSONObject("""{"pair_id":"tvp_0123456789ab","code":"K7Q2MZ","url":"https://oryksa.com/tv?c=K7Q2MZ",
            "short_url":"oryksa.com/tv","qr_png_url":"https://api.oryksa.com/v1/public/tv/qr/K7Q2MZ.png","expires_in":300,"events_url":"/v1/x"}"""))
        assertEquals("K7Q2MZ", p.code)
        assertEquals(300_000L, p.expiresAtMs - p.createdAtMs)
        val page = OryksaPhoneEventsPage.fromJson(JSONObject("""{"object":"tv_pair_events","state":"paired","phone":true,"next":2,
            "events":[{"seq":1,"type":"paired"},{"seq":2,"type":"listening"},
                      {"seq":3,"type":"text","text":"Como funciona?","lang":"pt-BR","final":true},{"seq":4,"type":"hologram"}]}"""), 0)
        assertEquals(4, page.next)
        assertEquals(true, page.phoneConnected)
        assertEquals(listOf(OryksaPhoneEventType.PAIRED, OryksaPhoneEventType.LISTENING, OryksaPhoneEventType.TEXT, OryksaPhoneEventType.UNKNOWN), page.events.map { it.type })
        assertEquals("Como funciona?", page.events[2].text)
        assertEquals("pt-BR", page.events[2].lang)
        assertEquals(7, OryksaPhoneEventsPage.fromJson(JSONObject("""{"state":"waiting","events":[]}"""), 7).next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPairingWithoutCodeIsRefused() {
        OryksaPairing.fromJson(JSONObject("""{"pair_id":"x"}"""))
    }

    @Test
    fun textForTheVoiceAndTheSubtitles() {
        val s = (0 until 12).joinToString(" ") { "Esta é a frase número $it da apresentadora." }
        val parts = oryksaTvSplitForSpeech(s, 120)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.length <= 120); assertTrue(it.endsWith(".")) }
        assertEquals(s, parts.joinToString(" "))
        assertEquals(listOf("Olá!", "Sou a ORYKSA.", "Vamos começar?"), oryksaTvSubtitleLines("Olá! Sou a ORYKSA. Vamos começar?"))
        assertEquals(1800L, oryksaTvReadingTimeMs("Olá!"))
        assertEquals(5000L, oryksaTvReadingTimeMs("a".repeat(70)))
    }

    @Test
    fun theTvPlatformsOfTheContract() {
        assertEquals(listOf("android_tv", "fire_tv", "vega", "tizen", "webos", "vidaa", "tvos", "roku"), OryksaTvPlatform.values().map { it.wire })
    }
}
