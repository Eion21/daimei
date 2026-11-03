package app.novel.source.http

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
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

    /**
     * The one chapter path that reports a failure instead of an empty chapter.
     *
     * Every other call here degrades to an empty result on purpose: a listing that cannot be read is a listing
     * with nothing in it, and a detail page that cannot be read is a novel the caller already holds. A chapter has
     * no such fallback — there is nothing to degrade to — so the only honest report of a failed fetch is to throw.
     * `ChapterContent` is non-null in the contract, and an empty `blocks` list reads as "this chapter has no text"
     * rather than "this chapter could not be fetched", which is how a single cut connection became a permanently
     * unreadable chapter: the empty answer was cached, and the cache answered before the source was ever asked
     * again. `GetChapterContent` catches this and resolves to a miss.
     *
     * @throws IOException when the chapter has no page to read, or its page could not be read.
     * @throws IllegalStateException when the page arrived but held nothing readable. A document with no blocks in
     *   it is a page this source does not understand, which is a different fact from a chapter whose text is empty
     *   and is reported as a different exception for exactly that reason.
     */
    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val chapterUrl = chapter.url?.takeIf { it.isNotBlank() }
            ?: throw IOException("Chapter ${chapter.id} carries no url, so its page cannot be read")
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(chapterUrl)?.groupValues?.get(1)
        val url = chapterUrl.takeIf { scheme == null || scheme.equals("http", true) || scheme.equals("https", true) }
            ?.let(::absolute)
            ?: throw IOException("Chapter ${chapter.id} carries a malformed url, so its page cannot be read")
        val html = get(url)
            ?: throw IOException("The chapter page at $url could not be read")

        val content = parseChapterContent(html, chapter)
        check(content.blocks.isNotEmpty()) {
            "The chapter page at $url parsed to no readable blocks, so it is not a chapter"
        }

        // Inline srcs are relative to the chapter document, not the library base or detail page.
        // Resolve here, where the page URL is known, before the content is cached or handed to Coil.
        return content.copy(
            blocks = content.blocks.map { block ->
                if (block is ContentBlock.Image) {
                    block.copy(
                        url = checkNotNull(url.resolve(block.url)) {
                            "Invalid inline image URL: ${block.url}"
                        }.toString(),
                    )
                } else {
                    block
                }
            },
        )
    }

    /**
     * Reads one document.
     *
     * Suspending is not enough on its own: [app.novel.source.api.NovelSource] is called from
     * Mihon's `Source` api, which runs its work on the main dispatcher, and OkHttp's `execute` is
     * blocking. [Dispatchers.IO] is what keeps this off the main thread, and unlike the callback
     * based alternative it still cancels with the calling scope.
     *
     * A transport failure reads as "no document" rather than an exception, because the browse
     * paths have nothing better to do with a page they could not read: a listing with no novels in
     * it is still a listing, and the reader for one is empty either way. [getChapterContent] is the
     * exception — it turns that null into a throw, because there the difference between "no
     * chapters" and "no connection" is the whole answer. There is no retry or backoff here on
     * purpose, per the plan.
     */
    private suspend fun get(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    // A page that answers with an error is a different thing from a page with no novels
                    // in it, and the browse screen renders both the same way.
                    logcat(LogPriority.WARN) { "HTTP ${response.code} fetching $url" }
                    null
                }
            }
        } catch (e: IOException) {
            // The null this returns is what a chapter page turns into a thrown failure, so without this line the
            // usual cause — the fixture server not running, or on another port — would reach the reader as a
            // message about the chapter rather than about a connection that never happened.
            // The throwable goes in the message rather than as an argument: `logcat`'s overload taking one
            // lives in `core:common`, which is an Android module and would cost this one its purity.
            logcat(LogPriority.ERROR) { "Could not reach $url: ${e.javaClass.simpleName}: ${e.message}" }
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
