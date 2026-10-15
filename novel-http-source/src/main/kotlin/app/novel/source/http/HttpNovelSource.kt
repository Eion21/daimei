package app.novel.source.http

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A [NovelSource] that really performs HTTP, against HTML this repository serves itself.
 *
 * It exists so the fetch half of the pipeline is exercised for real: every source before it was
 * [app.novel.source.demo.DemoSource], which touches no network, which made "the chapter opens
 * offline" a claim nothing could falsify. It is not a scraper. The only host it is pointed at is
 * the fixture server from `tools/serve-fixtures.sh`, and the page shapes it understands are the
 * ones in `src/test/resources/fixtures`.
 *
 * The split is deliberate and is what keeps the tests offline: this class fetches, and the
 * `parse*` functions in `NovelHtmlParser.kt` do all the reading, as pure functions over a String.
 * The url scheme the two halves agree on:
 *
 * | purpose      | address                                        |
 * |--------------|------------------------------------------------|
 * | listing      | `{base}index.html`                             |
 * | search       | `{base}search.html?q={query}`                  |
 * | detail       | `{base}novel/{novelId}.html`                   |
 * | chapter      | the `href` on the detail page, resolved there  |
 *
 * [app.novel.source.api.Novel] carries no url, so a novel is addressed by its id and the detail
 * address is derived. [Chapter] does carry one, and it comes back from [getChapters] absolute.
 *
 * Nothing is cached here. [app.novel.source.api.NovelSource] is a fetch contract and caching is
 * `ChapterContentRepository`'s, which already exists.
 */
class HttpNovelSource(
    baseUrl: String,
    private val httpClient: OkHttpClient,
) : NovelSource {

    /**
     * Resolved eagerly so a malformed base url fails where it was configured instead of at the
     * first request. [HttpUrl.parse] would defer that failure to every call site instead.
     */
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    override val info = SourceInfo(SOURCE_ID, "HTTP fixtures (local)", "1", "es")

    /**
     * The fixture server publishes a single listing page, so page 2 onwards answers empty
     * without touching the network. That is the same contract `DemoSource` has, and it is why
     * [parseNovelList] still reports `hasNextPage`: a site that did paginate would use it.
     */
    override suspend fun popular(page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)

        val html = get(listingUrl()) ?: return NovelPage(emptyList(), hasNextPage = false)
        return parseNovelList(html).withAbsoluteCovers()
    }

    override suspend fun search(query: String, page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)

        val html = get(searchUrl(query)) ?: return NovelPage(emptyList(), hasNextPage = false)
        return parseNovelList(html).withAbsoluteCovers()
    }

    /** The novel is passed back untouched when its page cannot be read, never blanked. */
    override suspend fun getNovelDetails(novel: Novel): Novel {
        val html = get(detailUrl(novel)) ?: return novel
        return parseNovelDetails(html, novel).withAbsoluteCover()
    }

    /**
     * Chapter hrefs are written relative to the detail page, so they are resolved against that
     * page's address rather than the base url. The result is absolute, which is what a caller
     * reopening a chapter out of the database needs.
     */
    override suspend fun getChapters(novel: Novel): List<Chapter> {
        val url = detailUrl(novel)
        val html = get(url) ?: return emptyList()

        return parseChapterList(html, novel).mapNotNull { chapter ->
            val href = chapter.url ?: return@mapNotNull null
            chapter.copy(url = url.resolve(href)?.toString())
        }
    }

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val url = chapter.url?.let(::absolute) ?: return ChapterContent(emptyList())
        val html = get(url) ?: return ChapterContent(emptyList())

        return parseChapterContent(html, chapter)
    }

    /**
     * Reads one document.
     *
     * Suspending is not enough on its own: [app.novel.source.api.NovelSource] is called from
     * Mihon's `Source` api, which runs its work on the main dispatcher, and OkHttp's `execute` is
     * blocking. [Dispatchers.IO] is what keeps this off the main thread, and unlike the callback
     * based alternative it still cancels with the calling scope.
     *
     * A transport failure reads as "no document" rather than an exception: an unreachable fixture
     * server should leave the reader empty, not crash it. There is no retry or backoff here on
     * purpose, per the plan.
     */
    private suspend fun get(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun listingUrl() = baseUrl.newBuilder().addPathSegment(LISTING).build()

    private fun searchUrl(query: String) = baseUrl.newBuilder()
        .addPathSegment(SEARCH)
        .addQueryParameter("q", query)
        .build()

    private fun detailUrl(novel: Novel) = baseUrl.newBuilder()
        .addPathSegment(DETAIL_DIRECTORY)
        .addPathSegment("${novel.id}.html")
        .build()

    /** A url that is already absolute is kept as it is; a relative one is read against the base. */
    private fun absolute(url: String) = url.toHttpUrlOrNull() ?: baseUrl.resolve(url)

    /**
     * The parser reports cover srcs exactly as the document wrote them, root relative ones
     * included, because it does not know where it is deployed. The reader does, so the source
     * turns them into something Coil can actually load.
     */
    private fun NovelPage.withAbsoluteCovers() = copy(novels = novels.map { it.withAbsoluteCover() })

    private fun Novel.withAbsoluteCover() = copy(coverUrl = coverUrl?.let { absolute(it)?.toString() })

    companion object {
        /**
         * Says what this is rather than pretending to be a browser. The fixture server does not
         * care, and a real one can answer a known client differently from an unknown one.
         */
        const val USER_AGENT = "Mihon-NovelHttpSource/1.0 (local development fixture source)"

        private const val FIRST_PAGE = 1
        private const val LISTING = "index.html"
        private const val SEARCH = "search.html"
        private const val DETAIL_DIRECTORY = "novel"
        private const val TIMEOUT_SECONDS = 30L

        /**
         * The source with the timeouts a real request needs. OkHttp defaults are generous enough
         * to look like a hang rather than a failure when the fixture server is not running.
         */
        fun create(baseUrl: String) = HttpNovelSource(
            baseUrl = baseUrl,
            httpClient = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }
}
