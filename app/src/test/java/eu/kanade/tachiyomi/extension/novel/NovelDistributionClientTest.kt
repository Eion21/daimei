package eu.kanade.tachiyomi.extension.novel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

internal class NovelDistributionClientTest {
    private val digest = "a".repeat(64)
    private val json = """{"protocol":"daimei-novel-distribution","schemaVersion":2,"repository":"test","extensions":[{"packageName":"app.novel.extension.fixtures","longVersionCode":1,"versionName":"1","novelApi":"1.1","entryClasses":["app.novel.extension.fixtures.Source"],"minSdk":26,"artifact":{"url":"https://assets.example.org/fixture.apk","size":1,"sha256":"$digest"},"currentSigners":["$digest"]}]}"""

    @Test fun `HTTPS schema2 metadata is returned without artifact or install actions`() {
        assertEquals("test", NovelDistributionCatalogCodec.decode(json.byteInputStream()).repository)
        var requests = 0
        val client = NovelDistributionClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertEquals("test", client.fetch("https://repo.example.org/catalog.json").repository)
        assertEquals(1, requests)
    }

    @Test fun `unsafe catalog URL is rejected before any request`() {
        var requests = 0
        val client = NovelDistributionClient(OkHttpClient.Builder().addInterceptor {
            requests++
            throw AssertionError("Rejected input reached transport")
        }.build())
        listOf("http://repo.example.org/catalog", "https://user:pass@repo.example.org/catalog", "https://repo.example.org/#fragment")
            .forEach { assertThrows(IllegalArgumentException::class.java) { client.fetch(it) } }
        assertEquals(0, requests)
    }

    @Test fun `HTTP redirect and encoded bodies are rejected without following targets`() {
        var requests = 0
        for ((status, encoding) in listOf(302 to "identity", 200 to "gzip")) {
            val client = NovelDistributionClient(OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("test")
                    .header("Content-Encoding", encoding).header("Location", "https://other.example.org/")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }.build())
            assertThrows(IOException::class.java) { client.fetch("https://repo.example.org/catalog.json") }
        }
        assertEquals(2, requests)
    }
}
