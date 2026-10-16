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
}
