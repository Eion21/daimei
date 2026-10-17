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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The failure contract of the host network, which is the part a caller cannot see any other way.
 *
 * The whole reason this class exists is to make "the fetch failed" and "the page was empty"
 * distinguishable, so the tests pin exactly that: a successful response yields its body, and both a
 * non-2xx response and a transport error throw rather than resolving to an empty string. A version of
 * this that swallowed the failure was how a chapter page got cached as an empty chapter and left a
 * black screen, which is the regression these assertions guard.
 *
 * OkHttp is mocked at its own boundary ([OkHttpClient], [Call]) but the [Response] is real, so the
 * `isSuccessful` and body-reading the class actually relies on are the real ones.
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
