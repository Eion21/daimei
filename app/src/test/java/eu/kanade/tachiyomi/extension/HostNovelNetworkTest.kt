package eu.kanade.tachiyomi.extension

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.app.di.HostNovelNetwork
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The failure contract of the host network, which is the part a caller cannot see any other way.
 *
 * The whole reason this class exists is to make "the fetch failed" and "the page was empty"
 * distinguishable, so the tests pin exactly that: a successful response yields its body, and both a
 * non-2xx response and a transport error throw rather than resolving to an empty string. A version of
 * this that swallowed the failure was how a chapter page got cached as an empty chapter and left a
 * black screen, which is the regression these assertions guard.
 *
 * Failure tests mock OkHttp at its boundary ([OkHttpClient], [Call]) with real [Response] bodies.
 * Header tests use the default client against a loopback HTTP server to verify the actual wire headers.
 */
class HostNovelNetworkTest {

    @Test
    fun `a successful response returns its body`() {
        val network = networkReturning(response(200, "chapter text"))

        val body = runBlocking { network.fetch("http://example.com/chapter") }

        assertEquals("chapter text", body)
    }

    @Test
    fun `a non-2xx response throws instead of returning an empty body`() {
        val network = networkReturning(response(404, "not found"))

        assertThrows(IOException::class.java) {
            runBlocking { network.fetch("http://example.com/missing") }
        }
    }

    @Test
    fun `a transport failure throws instead of returning an empty body`() {
        val call = mockk<Call>()
        every { call.execute() } throws IOException("connection refused")
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call

        assertThrows(IOException::class.java) {
            runBlocking { HostNovelNetwork(client).fetch("http://example.com/down") }
        }
    }

    /**
     * The one assertion that defends `fetchBytes` from being reimplemented on top of the text path.
     * A `body.string()` decode turns every byte here that is not valid UTF-8 into U+FFFD, so the
     * served bytes and the returned bytes differ the moment `.string()` replaces `.bytes()`. This is
     * the whole reason `fetchBytes` exists: a PDF is a binary body, and a text round-trip corrupts it.
     */
    @Test
    fun `fetchBytes returns the served bytes unchanged, including non-UTF-8 bytes`() {
        val bytes = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x41)
        val network = networkReturning(responseBytes(200, bytes))

        val body = runBlocking { network.fetchBytes("http://example.com/book.pdf") }

        assertArrayEquals(bytes, body)
    }

    @Test
    fun `fetchBytes throws on a non-2xx response instead of returning an empty array`() {
        val network = networkReturning(responseBytes(404, byteArrayOf(1, 2, 3)))

        assertThrows(IOException::class.java) {
            runBlocking { network.fetchBytes("http://example.com/missing.pdf") }
        }
    }

    @Test
    fun `default User-Agent identifies Daimei on the wire for text and byte fetches`() {
        for (fetchBytes in listOf(false, true)) {
            val servedBody = "chapter text".toByteArray(Charsets.UTF_8)
            LoopbackResponse(servedBody).use { server ->
                val network = HostNovelNetwork()

                if (fetchBytes) {
                    assertArrayEquals(servedBody, runBlocking { network.fetchBytes(server.url) })
                } else {
                    assertEquals("chapter text", runBlocking { network.fetch(server.url) })
                }

                assertEquals(
                    listOf("Daimei-NovelExtension/1.2 (host-provided network)"),
                    server.headerValues("User-Agent"),
                )
            }
        }
    }

    @Test
    fun `caller User-Agent overrides the default and additional headers reach the wire for both fetches`() {
        for (fetchBytes in listOf(false, true)) {
            val servedBody = "chapter text".toByteArray(Charsets.UTF_8)
            LoopbackResponse(servedBody).use { server ->
                val network = HostNovelNetwork()
                val headers = mapOf("uSeR-aGeNt" to "Extension-Test/1.0", "X-Novel-Test" to "caller-value")

                if (fetchBytes) {
                    assertArrayEquals(servedBody, runBlocking { network.fetchBytes(server.url, headers) })
                } else {
                    assertEquals("chapter text", runBlocking { network.fetch(server.url, headers) })
                }

                assertEquals(listOf("Extension-Test/1.0"), server.headerValues("User-Agent"))
                assertEquals(listOf("caller-value"), server.headerValues("X-Novel-Test"))
            }
        }
    }

    private class LoopbackResponse(body: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 5_000
        }
        val url = "http://127.0.0.1:${server.localPort}/chapter"
        private val exchange = FutureTask {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                assertEquals("GET /chapter HTTP/1.1", reader.readLine())
                val headers = buildList {
                    while (true) {
                        val line = checkNotNull(reader.readLine()) { "Connection closed before HTTP headers ended" }
                        if (line.isEmpty()) break
                        add(line)
                    }
                }
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
                headers
            }
        }
        private val worker = Thread(exchange, "novel-network-loopback").apply { start() }

        fun headerValues(name: String): List<String> = exchange.get(5, TimeUnit.SECONDS)
            .filter { it.substringBefore(':').equals(name, ignoreCase = true) }
            .map { it.substringAfter(':').trim() }

        override fun close() {
            server.close()
            worker.join(6_000)
            assertFalse(worker.isAlive, "Loopback server thread did not finish")
            // Surface server failures even when a client call or an assertion failed inside use.
            exchange.get(1, TimeUnit.SECONDS)
        }
    }

    private fun networkReturning(response: Response): HostNovelNetwork {
        val call = mockk<Call>()
        every { call.execute() } returns response
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call
        return HostNovelNetwork(client)
    }

    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("http://example.com/chapter").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(ResponseBody.create(null, body.toByteArray()))
        .build()

    private fun responseBytes(code: Int, body: ByteArray): Response = Response.Builder()
        .request(Request.Builder().url("http://example.com/book.pdf").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(ResponseBody.create(null, body))
        .build()
}
