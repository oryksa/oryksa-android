package com.oryksa.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OryksaClientTest {
    @Test
    fun agentParsingAndLanguageFallbacks() {
        val a = OryksaAgent.fromJson(JSONObject("""{"name":"ORYKSA","avatar":"https://x/a.jpg","greeting":{"en":"Hi","pt":"Olá"},"suggestions":{"en":["Prices?"]},"voice_replies":true}"""))
        assertEquals("ORYKSA", a.name)
        assertTrue(a.voiceReplies)
        assertEquals("Olá", OryksaAgent.pick(a.greeting, "br"))
        assertEquals("Hi", OryksaAgent.pick(a.greeting, "es"))
        assertEquals(listOf("Prices?"), OryksaAgent.pick(a.suggestions, "pt"))
    }

    @Test
    fun missingFieldsUseDefaults() {
        val a = OryksaAgent.fromJson(JSONObject("{}"))
        assertEquals("ORYKSA", a.name)
        assertEquals(OryksaAgent.DEFAULT_AVATAR, a.avatar)
        assertNull(a.conversationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun secretKeyIsRefused() {
        OryksaClient(token = "oryk_live_abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun tokenIsRequired() {
        OryksaClient()
    }
}
