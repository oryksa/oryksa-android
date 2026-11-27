package com.oryksa.sdk.tv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket

/** One request seen by [TvServer]. */
data class TvRequest(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

/** One answer of [TvServer]: status, body bytes and extra headers. */
class TvAnswer(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun json(status: Int, json: String) = TvAnswer(status, json.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json"))
    }
}

/** A tiny local HTTP server for the TV backend tests (method, headers and binary bodies), one request per connection. */
class TvServer(private val answer: (TvRequest) -> TvAnswer) : AutoCloseable {
    val seen: MutableList<TvRequest> = java.util.Collections.synchronizedList(mutableListOf())
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base: String get() = "http://127.0.0.1:${socket.localPort}/v1"

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
        }
    }

    init {
        Thread {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                s.use { c ->
                    val input = c.getInputStream()
                    val requestLine = readLine(input) ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val l = readLine(input) ?: break
                        if (l.isEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var got = 0
                    while (got < length) {
                        val n = input.read(body, got, length - got)
                        if (n < 0) break
                        got += n
                    }
                    val parts = requestLine.split(" ")
                    val target = parts.getOrElse(1) { "/" }
                    val r = TvRequest(parts[0], target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, String(body, 0, got, Charsets.UTF_8))
                    seen.add(r)
                    val a = try { answer(r) } catch (e: Throwable) { TvAnswer.json(599, "{\"error\":{\"code\":\"test\",\"message\":\"${e.message}\"}}") }
                    val head = StringBuilder("HTTP/1.1 ${a.status} X\r\n")
                    a.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                    head.append("Content-Length: ${a.body.size}\r\nConnection: close\r\n\r\n")
                    val out = c.getOutputStream()
                    out.write(head.toString().toByteArray(Charsets.UTF_8))
                    out.write(a.body)
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = socket.close()
}
