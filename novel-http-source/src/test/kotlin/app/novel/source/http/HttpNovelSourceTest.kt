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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `inline images resolve against the actual chapter page without losing text or alt`() = runBlocking {
        val html = """<div class="chapter-content"><p>Antes.</p>
            <img src="illustrations/one.png" alt="Uno">
            <img src="../images/two.png" alt="Dos">
            <img src="/images/three.png" alt="Tres">
            <img src="//cdn.example/four.png" alt="Cuatro">
            <img src="https://cdn.example/five.png" alt="Cinco"><p>Después.</p></div>"""
        val (source, wire) = source(overrides = mapOf(CHAPTER_PATH to html))
        val content = source.getChapterContent(chapterOne())
        assertEquals(listOf(CHAPTER_URL), wire.requestedUrls)
        assertEquals(
            listOf(
                ContentBlock.Paragraph("Antes."),
                ContentBlock.Image("$HOST/chapter/illustrations/one.png", "Uno"),
                ContentBlock.Image("$HOST/images/two.png", "Dos"),
                ContentBlock.Image("$HOST/images/three.png", "Tres"),
                ContentBlock.Image("http://cdn.example/four.png", "Cuatro"),
                ContentBlock.Image("https://cdn.example/five.png", "Cinco"),
                ContentBlock.Paragraph("Después."),
            ),
            content.blocks,
        )
    }

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
    fun `a chapter with no url fails without a request instead of reading as an empty chapter`() = runBlocking {
        // There is no page to read, so there is no chapter to return. Returning `{"blocks":[]}` here is what
        // wrote the poisoned cache on the device.
        val (source, wire) = source()
        wire.requestedUrls.clear()

        assertFailsWith<IOException> {
            source.getChapterContent(Chapter(SOURCE_ID, "torre-de-cristal", "x", "Sin url", url = null))
        }
        assertEquals(emptyList(), wire.requestedUrls)
    }

    @Test
    fun `a chapter page that is not served fails instead of reading as an empty chapter`() = runBlocking<Unit> {
        val (source, _) = source(excluding = setOf("/chapter/torre-de-cristal-1.html"))
        val novel = source.popular(1).novels.first()
        val chapter = source.getChapters(novel)[1]

        // The exact device failure: the chapter was opened offline, the source could not be reached, and an
        // `{"blocks":[]}` was stored as if it were the chapter. Answering from that cache forever afterwards is
        // what turned one cut connection into a permanently unreadable chapter.
        assertFailsWith<IOException> { source.getChapterContent(chapter) }
    }

    @Test
    fun `a fetch that never reaches the server fails instead of reading as an empty chapter`() = runBlocking<Unit> {
        val source = offlineSource()

        assertFailsWith<IOException> { source.getChapterContent(chapterOne()) }
    }

    @Test
    fun `a chapter page that parses to nothing fails instead of reading as an empty chapter`() = runBlocking<Unit> {
        // The document arrived and held no readable block. That is a page the parser could not read, not a
        // chapter whose text is empty, and reporting it as the second is how a zero-block chapter gets written.
        val (source, _) = source(overrides = mapOf(CHAPTER_PATH to PAGE_WITHOUT_BLOCKS))

        assertFailsWith<IllegalStateException> { source.getChapterContent(chapterOne()) }
    }

    @Test
    fun `the source identifies itself`() {
        val (source, _) = source()

        assertEquals(SOURCE_ID, source.info.id)
        assertTrue(source.info.name.isNotBlank())
    }

    /**
     * [overrides] replaces what a path answers with, so a test can serve a document shape the fixture set does
     * not carry without adding a file for every case.
     */
    private fun source(
        excluding: Set<String> = emptySet(),
        overrides: Map<String, String> = emptyMap(),
    ): Pair<HttpNovelSource, RecordingInterceptor> {
        val interceptor = RecordingInterceptor(
            ROUTES.filterKeys { it !in excluding }.mapValues { (_, name) -> fixture(name) } + overrides,
        )
        return HttpNovelSource(
            baseUrl = BASE_URL,
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        ) to interceptor
    }

    /** The device with no network: the call never leaves the process, exactly as a transport failure does. */
    private fun offlineSource() = HttpNovelSource(
        baseUrl = BASE_URL,
        httpClient = OkHttpClient.Builder().addInterceptor(OfflineInterceptor()).build(),
    )

    /** The handle [getChapters] would hand back for chapter 1, without a listing request to produce one. */
    private fun chapterOne() = Chapter(
        sourceId = SOURCE_ID,
        novelId = "torre-de-cristal",
        id = "torre-de-cristal-1",
        title = "El ascensor",
        url = CHAPTER_URL,
    )

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

    /** Throws the way OkHttp does when the connection was never made, which is what an offline device looks like. */
    private class OfflineInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = throw IOException("no route to host")
    }

    private companion object {
        /** Split so assertions read `$HOST/index.html` and do not end up with a doubled slash. */
        const val HOST = "http://10.0.2.2:8080"
        const val BASE_URL = "$HOST/"

        const val CHAPTER_PATH = "/chapter/torre-de-cristal-1.html"
        const val CHAPTER_URL = "$HOST$CHAPTER_PATH"

        val ROUTES = mapOf(
            "/index.html" to "index.html",
            "/search.html" to "search.html",
            "/novel/torre-de-cristal.html" to "novel/torre-de-cristal.html",
            CHAPTER_PATH to "chapter/torre-de-cristal-1.html",
        )

        /**
         * A chapter page whose content container holds nothing a reader can read: the ad block and the script
         * are there, and both are stripped. Everything the fixture set serves has at least one paragraph, so this
         * shape has to be written out.
         */
        val PAGE_WITHOUT_BLOCKS = """
            <html lang="es">
            <body>
              <article class="chapter">
                <h1 class="chapter-title">Capitulo 1: El ascensor</h1>
                <div class="chapter-content">
                  <script>window.__CHAPTER_ADS__ = ["banner-a"];</script>
                  <div class="ad-slot" data-ad-network="fixture">Patrocinado por el Sponsor Ficticio</div>
                </div>
              </article>
            </body>
            </html>
        """.trimIndent()

        fun fixture(name: String): String =
            checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) {
                "missing fixture: fixtures/$name"
            }.bufferedReader().use { it.readText() }
    }
}
