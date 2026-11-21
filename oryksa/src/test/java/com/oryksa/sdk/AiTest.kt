package com.oryksa.sdk

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

// Owner 2026-11-21: the SDK lets the developer have the AI do what THEY want in the app,
// counted as ORYKSA interactions.
class AiTest {
    @Test
    fun aiSendsInstructionsPhotosAndSchemaAndReadsTheJson() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        TinyServer { target, _, body ->
            assertEquals("/v1/client/ai", target)
            bodies.add(JSONObject(body))
            200 to """{"object":"ai_result","text":"{\"size\":\"205/55 R16 91V\"}","json":{"size":"205/55 R16 91V"},"oryksa":{"interactions_spent":1,"interactions_remaining":99}}"""
        }.use { server ->
            val c = OryksaClient(token = "oryk_cs_test", baseUrl = "http://127.0.0.1:${server.port}/v1")
            val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
            val r = c.ai(
                instructions = "Read the tyre size. Never invent.",
                images = listOf(OryksaImage(jpg)),
                schema = JSONObject().put("type", "object"),
            )
            val b = bodies.single()
            assertEquals("Read the tyre size. Never invent.", b.getString("instructions"))
            val img = b.getJSONArray("images").getJSONObject(0)
            assertEquals(Base64.getEncoder().encodeToString(jpg), img.getString("base64"))
            assertEquals("image/jpeg", img.getString("mime"))
            assertTrue(b.has("schema"))
            assertFalse(b.has("message"))
            assertEquals("205/55 R16 91V", r.json?.getString("size"))
            assertEquals(1, r.interactionsSpent)
            assertEquals(99, r.interactionsRemaining)
        }
    }

    @Test
    fun aiWithoutSchemaGivesTextAndNoJson() {
        val r = OryksaClient.aiResult(JSONObject("""{"object":"ai_result","text":"Lisboa.","json":null,"oryksa":{"interactions_spent":1}}"""))
        assertEquals("Lisboa.", r.text)
        assertNull(r.json)
        assertNull(r.interactionsRemaining)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyCallIsRefused() {
        OryksaClient.aiBody()
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanFourPhotosIsRefused() {
        val p = OryksaImage(byteArrayOf(1))
        OryksaClient.aiBody(input = "x", images = listOf(p, p, p, p, p))
    }
}
