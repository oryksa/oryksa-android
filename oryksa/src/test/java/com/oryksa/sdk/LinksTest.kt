package com.oryksa.sdk

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

// The reply the TugaFreela app showed as plain text on 21/11/2026 (owner: "os links ainda não estão clicáveis").
private const val REPLY = "Sim, encontrei alguns webdesigners para criar sua landing page. Você pode conferir este serviço de " +
    "landing page profissional focada em conversões:\n" +
    "https://tugafreela.pt/wharakawa/criar-uma-landing-page-profissional-focada-em-conversoes\n" +
    "Se quiser ver mais opções, aqui está a lista: https://tugafreela.pt/servico/explorar/28."

private const val FIRST = "https://tugafreela.pt/wharakawa/criar-uma-landing-page-profissional-focada-em-conversoes"
private const val SECOND = "https://tugafreela.pt/servico/explorar/28"

class LinksTest {
    @Test
    fun theLinksOfAReplyInOrderWithoutTheFinalDot() {
        assertEquals(listOf(FIRST, SECOND), oryksaLinksIn(REPLY))
        assertEquals(listOf("https://x.pt/a", "https://y.pt/b", "https://z.pt/c"),
            oryksaLinksIn("a https://x.pt/a, b https://x.pt/a! c https://y.pt/b; d https://z.pt/c? e https://w.pt/d"))
        assertEquals(listOf("https://x.pt/a", "https://y.pt/b"), oryksaLinksIn("(see https://x.pt/a) or \"https://y.pt/b\"…"))
        assertEquals(emptyList<String>(), oryksaLinksIn("no links here, write to info@x.pt"))
    }

    @Test
    fun theVoiceScreenTextHasNoAddressesAndNoDanglingColon() {
        val t = oryksaWithoutLinks(REPLY)
        assertFalse(t.contains("http"))
        assertFalse(t.contains("conversões:"))
        assertTrue(t.contains("focada em conversões"))
        assertFalse(t.endsWith(":"))
        assertEquals("See.", oryksaWithoutLinks("See https://x.pt/a ."))
    }

    @Test
    fun theLinksInTheTextCanBeTappedAndGoToTheApp() {
        val tapped = mutableListOf<String>()
        val text = "**Snowboard** at $FIRST. Mail info@tugafreela.pt or $SECOND."
        val rich = oryksaRichText(text, Color.Green) { tapped.add(it) }
        assertEquals("Snowboard at $FIRST. Mail info@tugafreela.pt or $SECOND.", rich.text)
        val links = rich.getLinkAnnotations(0, rich.length)
        assertEquals(listOf(FIRST, "info@tugafreela.pt", SECOND), links.map { rich.text.substring(it.start, it.end) })
        assertEquals(listOf(FIRST, "mailto:info@tugafreela.pt", SECOND), links.map { (it.item as LinkAnnotation.Url).url })
        assertTrue(links.all { (it.item as LinkAnnotation.Url).styles?.style?.color == Color.Green })
        val first = links.first().item as LinkAnnotation.Url
        first.linkInteractionListener!!.onClick(first)
        assertEquals(listOf(FIRST), tapped)
        val bold = rich.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { rich.text.substring(it.start, it.end) }
        assertEquals(listOf("Snowboard"), bold)
    }

    @Test
    fun theCardShowsTheTitleOrTheHost() {
        assertEquals("tugafreela.pt" to "tugafreela.pt", oryksaCardTexts("https://www.tugafreela.pt/x", null))
        val p = OryksaLinkPreview(FIRST, title = "Landing page", site = "TugaFreela", price = "€150")
        assertEquals("Landing page" to "€150 · TugaFreela", oryksaCardTexts(FIRST, p))
        assertEquals("Landing page" to "TugaFreela", oryksaCardTexts(FIRST, p.copy(price = "")))
        assertEquals("tugafreela.pt" to "tugafreela.pt", oryksaCardTexts(FIRST, OryksaLinkPreview(FIRST)))
    }

    @Test
    fun thePreviewComesFromTheServerOnceAndIsKept() = runBlocking {
        val asked = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        var status = 200
        TinyServer { target, headers, _ ->
            assertTrue(target.startsWith("/v1/client/link-preview?url="))
            asked.add(URLDecoder.decode(target.substringAfter("?url="), "UTF-8"))
            auth.add(headers["authorization"])
            status to (if (status == 200) JSONObject().put("object", "link_preview").put("url", asked.last())
                .put("title", "Eu vou criar uma landing page profissional focada em conversões").put("image", "")
                .put("site", "tugafreela.pt").put("price", JSONObject.NULL).toString()
            else """{"error":{"code":"boom","message":"no"}}""")
        }.use { server ->
            val c = OryksaClient(token = "oryk_cs_test", baseUrl = "http://127.0.0.1:${server.port}/v1")
            val p = c.linkPreview(FIRST)
            assertEquals("Eu vou criar uma landing page profissional focada em conversões", p?.title)
            assertEquals("tugafreela.pt", p?.site)
            assertEquals("", p?.price)
            assertEquals(listOf(FIRST), asked)
            assertEquals("Bearer oryk_cs_test", auth.first())
            assertEquals(p, c.linkPreview(FIRST))
            assertEquals(1, asked.size)

            val odd = "https://shop.pt/p?id=5&c=a+b#top"
            c.linkPreview(odd)
            assertEquals(odd, asked.last())

            status = 500
            assertNull(c.linkPreview("https://tugafreela.pt/other"))
        }
    }
}

/** A tiny HTTP server for the tests (one answer per request, no network outside this machine; ASCII bodies). */
internal class TinyServer(private val answer: (target: String, headers: Map<String, String>, body: String) -> Pair<Int, String>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                s.use { c ->
                    val reader = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val sent = CharArray(length)
                    var got = 0
                    while (got < length) {
                        val n = reader.read(sent, got, length - got)
                        if (n < 0) break
                        got += n
                    }
                    val (status, body) = try { answer(requestLine.split(" ")[1], headers, String(sent, 0, got)) } catch (e: Throwable) { 599 to "${e.message}" }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val out = c.getOutputStream()
                    val head = listOf("HTTP/1.1 $status X", "Content-Type: application/json; charset=utf-8",
                        "Content-Length: ${bytes.size}", "Connection: close", "", "").joinToString("\r\n")
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun close() = socket.close()
}
