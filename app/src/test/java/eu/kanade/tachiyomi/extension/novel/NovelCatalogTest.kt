package eu.kanade.tachiyomi.extension.novel

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class NovelCatalogTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/s38/local-test.json"))
        .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun decode(json: String) = NovelCatalogCodec.decode(json.byteInputStream())

    private fun rejects(reason: NovelCatalogError, json: String) {
        val bytes = json.toByteArray()
        val before = bytes.copyOf()
        val error = assertThrows(NovelCatalogException::class.java) {
            NovelCatalogCodec.decode(ByteArrayInputStream(bytes))
        }
        assertEquals(reason, error.reason)
        assertTrue(bytes.contentEquals(before), "Rejected input must remain unchanged")
    }

    @Test
    fun `S36 fixture is metadata available but never host compatible or installable`() {
        val catalog = decode(fixture())
        assertEquals(3, catalog.extensions.size)
        assertEquals("app.novel.extension.jabrascan", catalog.extensions.first().id)
        assertEquals("1.3", catalog.extensions.first().novelApi)
        assertEquals(4L, catalog.extensions.first().versionCode)
        assertFalse(catalog.hostCompatible)
        assertFalse(catalog.canInstall)
    }

    @Test
    fun `manga legacy modern and source ids are not novel catalogs`() {
        val examples = listOf(
            "[]",
            "{\"name\":\"Manga\",\"signingKey\":\"x\"}",
            "{\"extensions\":[{\"sources\":[{\"id\":123}]}]}",
        )
        for (json in examples) {
            rejects(NovelCatalogError.SCHEMA, json)
        }
        rejects(NovelCatalogError.SCHEMA, fixture().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    }

    @Test
    fun `closed schema refuses unknown missing and wrong type fields`() {
        val original = fixture()
        for (json in listOf(
            original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"extensionLib\": \"1.4\""),
            original.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
            original.replace("\"versionCode\": 4", "\"versionCode\": true"),
            original.replace("\"versionCode\": 4", "\"versionCode\": 4.0"),
            original.replace("\"novelApi\": \"1.3\"", "\"novelApi\": 1.3"),
            original.replace("\"downloadUrl\": null,", ""),
            original.replace("\"hostCompatible\": false", "\"hostCompatible\": \"false\""),
        )) {
            rejects(NovelCatalogError.SCHEMA, json)
        }
    }

    @Test
    fun `local proposal cannot promote compatibility signing purpose or download urls`() {
        val original = fixture()
        for (json in listOf(
            original.replace("\"hostCompatible\": false", "\"hostCompatible\": true"),
            original.replace("\"local-test\"", "\"published\""),
            original.replace("\"development-only\"", "\"production\""),
            original.replace("\"downloadUrl\": null", "\"downloadUrl\": \"https://catalog.invalid/test.apk\""),
            original.replace("\"novelApi\": \"1.3\"", "\"novelApi\": \"9.9\""),
        )) {
            rejects(NovelCatalogError.SCHEMA, json)
        }
    }

    @Test
    fun `unsafe identity hash size filenames and duplicate entries are rejected`() {
        val original = fixture()
        for (json in listOf(
            original.replace("app.novel.extension.jabrascan\"", "app.novel.extension.jabrascan-123\""),
            original.replace("novel-jabrascan-extension-debug.apk", "../outside.apk"),
            original.replace("\"size\": 9201981", "\"size\": 0"),
            original.replace("\"minSdk\": 26", "\"minSdk\": 25"),
            original.replace("\"versionCode\": 4", "\"versionCode\": -1"),
            original.replace("app.novel.extension.matchanovels\"", "app.novel.extension.jabrascan\""),
            original.replace("novel-matchanovels-extension-debug.apk", "novel-jabrascan-extension-debug.apk"),
            original.replace("8c825a8b3ba2ce16724a25327c1c1f4db403d21389946d0c521a21d537c232f2", "invalid"),
        )) {
            rejects(NovelCatalogError.SCHEMA, json)
        }
    }

    @Test
    fun `malformed UTF8 JSON duplicate keys and deeply nested responses fail safely`() {
        for (json in listOf("{", fixture().dropLast(4), "{\"a\":1,\"a\":2}", "[".repeat(40) + "]".repeat(40))) {
            rejects(NovelCatalogError.JSON, json)
        }
        val error = assertThrows(NovelCatalogException::class.java) {
            NovelCatalogCodec.decode(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertEquals(NovelCatalogError.JSON, error.reason)
    }

    @Test
    fun `reader stops at two MB plus one without trusting declared content length`() {
        var consumed = 0
        val endless = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 32
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(32, offset, offset + length)
                consumed += length
                return length
            }
        }
        val error = assertThrows(NovelCatalogException::class.java) { NovelCatalogCodec.decode(endless) }
        assertEquals(NovelCatalogError.SIZE, error.reason)
        assertEquals(NovelCatalogCodec.MAX_BYTES + 1, consumed)
        val base = fixture()
        val exact = base + " ".repeat(NovelCatalogCodec.MAX_BYTES - base.toByteArray().size)
        assertEquals(3, decode(exact).extensions.size)
    }

    @Test
    fun `HTTPS read only query returns actual package identities and closes response`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("GET", chain.request().method)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(fixture().toResponseBody()).build()
        }.build()
        val catalog = NovelCatalogClient(client).fetch("https://catalog.invalid/local-test.json")
        assertEquals(1, calls.get())
        assertEquals("app.novel.extension.matchanovels", catalog.extensions[1].id)
        assertFalse(catalog.canInstall)
    }

    @Test
    fun `disallowed query URLs are refused before any network call`() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw IOException("Unexpected network call")
        }.build()
        val urls = listOf(
            "http://catalog.invalid/a",
            "file:///tmp/catalog.json",
            "https://user:password@catalog.invalid/a",
            "https://catalog.invalid/a#fragment",
            "not a URL",
        )
        for (url in urls) {
            val error = assertThrows(NovelCatalogException::class.java) {
                runBlocking { NovelCatalogClient(client).fetch(url) }
            }
            assertEquals(NovelCatalogError.URL, error.reason)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `network HTTP redirects and excess response sizes are visible failures not empty catalogs`() {
        for ((code, body, reason) in listOf(
            Triple(503, "", NovelCatalogError.HTTP),
            Triple(302, "", NovelCatalogError.HTTP),
            Triple(200, " ".repeat(NovelCatalogCodec.MAX_BYTES + 1), NovelCatalogError.SIZE),
            Triple(200, "not JSON", NovelCatalogError.JSON),
        )) {
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .header("Location", "http://other.invalid/").body(body.toResponseBody()).build()
            }.build()
            val error = assertThrows(NovelCatalogException::class.java) {
                runBlocking { NovelCatalogClient(client).fetch("https://catalog.invalid/a") }
            }
            assertEquals(reason, error.reason)
            assertEquals(1, calls.get())
        }
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val error = assertThrows(NovelCatalogException::class.java) {
            runBlocking { NovelCatalogClient(client).fetch("https://catalog.invalid/a") }
        }
        assertEquals(NovelCatalogError.NETWORK, error.reason)
    }
}
