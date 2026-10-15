package app.novel.source.http

import app.novel.source.api.Chapter
import app.novel.source.api.ContentBlock
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the fetch half of [HttpNovelSource] offline.
 *
 * The client is given an interceptor that answers from the fixture map and never calls
 * `chain.proceed`, so no socket is opened and no request leaves the machine. What this buys
 * over a parser-only test is the wiring that used to be dead code: url building, relative href
 * resolution, the `User-Agent` header and the response handling all really run.
 */
class HttpNovelSourceTest {

    @Test
    fun `popular requests the listing url with the source user agent`() = runBlocking {
        val (source, wire) = source()

        val page = source.popular(1)

        assertEquals(listOf("$HOST/index.html"), wire.requestedUrls)
        assertEquals(listOf<String?>(HttpNovelSource.USER_AGENT), wire.requestedUserAgents)
        assertEquals(
            listOf("La torre de cristal", "Cronicas del valle", "Quimera urbana"),
            page.novels.map { it.title },
        )
    }

    @Test
    fun `the source absolutizes the document relative covers the parser reports`() = runBlocking {
        val (source, _) = source()

        val page = source.popular(1)
        val details = source.getNovelDetails(page.novels.first())

        // The parser hands back `/covers/x.jpg` because it does not know where it is deployed.
        // Coil cannot load that, so the source is the layer that has to resolve it.
        assertEquals(
            listOf(
                "$HOST/covers/torre-de-cristal.jpg",
                "$HOST/covers/cronicas-del-valle.jpg",
                "$HOST/covers/quimera-urbana.jpg",
            ),
            page.novels.map { it.coverUrl },
        )
        assertEquals("$HOST/covers/torre-de-cristal.jpg", details.coverUrl)
    }

    @Test
    fun `popular past the first page is empty and issues no request`() = runBlocking {
        val (source, wire) = source()

        val page = source.popular(2)

        assertEquals(emptyList(), page.novels)
        assertTrue(!page.hasNextPage)
        assertEquals(emptyList(), wire.requestedUrls, "the second page should not hit the network")
    }

    @Test
    fun `search encodes the query into the search url`() = runBlocking {
        val (source, wire) = source()

        val page = source.search("torre de cristal", 1)

        assertEquals(listOf("$HOST/search.html?q=torre%20de%20cristal"), wire.requestedUrls)
        assertEquals(listOf("La torre de cristal"), page.novels.map { it.title })
    }

    @Test
    fun `getNovelDetails derives the detail url from the novel id`() = runBlocking {
        val (source, wire) = source()
        val novel = source.popular(1).novels.first()

        val details = source.getNovelDetails(novel)

        assertEquals(listOf("$HOST/index.html", "$HOST/novel/torre-de-cristal.html"), wire.requestedUrls)
        assertEquals("Ada Vergara", details.author)
        assertTrue(details.description!!.startsWith("Unaginx ingeniera descubre"))
    }

    @Test
    fun `getChapters resolves chapter links against the detail page`() = runBlocking {
        val (source, wire) = source()
        val novel = source.popular(1).novels.first()

        val chapters = source.getChapters(novel)
        val content = source.getChapterContent(chapters[1])

        // `../chapter/x.html` has to resolve against /novel/torre-de-cristal.html. Resolving it
        // against the bare base url would look right until the base url ever had a path.
        assertEquals("$HOST/chapter/torre-de-cristal-1.html", wire.requestedUrls.last())
        assertEquals(4, chapters.size)
        assertEquals("$HOST/chapter/torre-de-cristal-1.html", chapters[1].url)
        assertEquals(ContentBlock.Heading("El ascensor", level = 2), content.blocks.first())
    }

    @Test
    fun `getChapterContent strips the noise it fetched`() = runBlocking {
        val (source, _) = source()
        val novel = source.popular(1).novels.first()

        val content = source.getChapterContent(source.getChapters(novel)[1])

        val rendered = content.blocks.filterIsInstance<ContentBlock.Paragraph>().joinToString(" ") { it.text }
        assertTrue("Patrocinado por el Sponsor Ficticio" !in rendered)
        assertTrue("__CHAPTER_ADS__" !in rendered)
        assertEquals("El ascensor", content.blocks.filterIsInstance<ContentBlock.Heading>().single().text)
    }

    @Test
    fun `a page that is not served yields empty results instead of throwing`() = runBlocking {
        val (source, _) = source(excluding = setOf("/novel/torre-de-cristal.html"))
        val novel = source.popular(1).novels.first()

        assertEquals(novel, source.getNovelDetails(novel))
        assertEquals(emptyList(), source.getChapters(novel))
    }

    @Test
    fun `a chapter with no url yields empty content without a request`() = runBlocking {
        val (source, wire) = source()
        wire.requestedUrls.clear()

        val content = source.getChapterContent(Chapter(SOURCE_ID, "torre-de-cristal", "x", "Sin url", url = null))

        assertEquals(emptyList(), content.blocks)
        assertEquals(emptyList(), wire.requestedUrls)
    }

    @Test
    fun `the source identifies itself`() {
        val (source, _) = source()

        assertEquals(SOURCE_ID, source.info.id)
        assertTrue(source.info.name.isNotBlank())
    }

    private fun source(excluding: Set<String> = emptySet()): Pair<HttpNovelSource, RecordingInterceptor> {
        val interceptor = RecordingInterceptor(
            ROUTES.filterKeys { it !in excluding }.mapValues { (_, name) -> fixture(name) },
        )
        return HttpNovelSource(
            baseUrl = BASE_URL,
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        ) to interceptor
    }

    /** Answers from a fixed table and never delegates down the chain, so no socket is opened. */
    private class RecordingInterceptor(private val routes: Map<String, String>) : Interceptor {
        val requestedUrls = mutableListOf<String>()
        val requestedUserAgents = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requestedUrls += request.url.toString()
            requestedUserAgents += request.header("User-Agent")

            val body = routes[request.url.encodedPath]
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 404)
                .message(if (body != null) "OK" else "Not Found")
                .body((body ?: "not found").toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
    }

    private companion object {
        /** Split so assertions read `$HOST/index.html` and do not end up with a doubled slash. */
        const val HOST = "http://10.0.2.2:8080"
        const val BASE_URL = "$HOST/"

        val ROUTES = mapOf(
            "/index.html" to "index.html",
            "/search.html" to "search.html",
            "/novel/torre-de-cristal.html" to "novel/torre-de-cristal.html",
            "/chapter/torre-de-cristal-1.html" to "chapter/torre-de-cristal-1.html",
        )

        fun fixture(name: String): String =
            checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
                "missing fixture: fixtures/$name"
            }.bufferedReader().use { it.readText() }
    }
}
