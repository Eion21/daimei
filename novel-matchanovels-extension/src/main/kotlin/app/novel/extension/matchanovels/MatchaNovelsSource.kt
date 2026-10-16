package app.novel.extension.matchanovels

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo

/**
 * The first real novel source: `https://matchanovels.com/`, a server-rendered Next.js site whose
 * pages arrive as plain HTML, so no JavaScript, cookies or login are needed.
 *
 * It is a [NetworkedNovelSource]: it ships no HTTP client and never touches a dispatcher. It asks
 * the host's [NovelNetwork] for a page and hands the String to [MatchaNovelsParsing], which owns
 * the site-specific parsing. The host owns the socket, the timeouts, the `User-Agent` and the
 * main-thread offload.
 *
 * Every method is a thin fetch-then-parse: the interesting decisions live in the parsing object,
 * where they can be pinned by a pure JVM test.
 */
class MatchaNovelsSource : NetworkedNovelSource {

    /**
     * Injected through [bindNetwork] before the host registers this source. `lateinit` rather than a
     * nullable field so a source reached before binding fails loudly instead of silently fetching
     * nothing.
     */
    private lateinit var network: NovelNetwork

    override val info = SourceInfo(
        id = MatchaNovelsParsing.SOURCE_ID,
        name = "MatchaNovels",
        version = "1.0.0",
        language = "es",
    )

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    /** The whole catalogue lives on one page, so any page but the first is empty (decision D5). */
    override suspend fun popular(page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)
        return NovelPage(MatchaNovelsParsing.parseCatalogue(network.fetch(LIBRARY_URL)), hasNextPage = false)
    }

    /**
     * The site searches client-side, so the catalogue is fetched and filtered here (decision D2).
     * There is no search endpoint to call: a `?q=` is accepted and ignored by the site.
     */
    override suspend fun search(query: String, page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)
        val catalogue = MatchaNovelsParsing.parseCatalogue(network.fetch(LIBRARY_URL))
        return NovelPage(MatchaNovelsParsing.filterByTitle(catalogue, query), hasNextPage = false)
    }

    override suspend fun getNovelDetails(novel: Novel): Novel =
        MatchaNovelsParsing.parseNovelDetails(network.fetch(novelUrl(novel.id)), novel)

    override suspend fun getChapters(novel: Novel): List<Chapter> =
        MatchaNovelsParsing.parseChapterList(network.fetch(novelUrl(novel.id)), novel)

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val url = chapter.url
            ?: throw IllegalStateException("Chapter ${chapter.id} carries no url, so its page cannot be read")
        val content = MatchaNovelsParsing.parseChapterContent(network.fetch(url))
        // Zero blocks is a statement about the document, not about the chapter, so it is reported as
        // a failure rather than passed on as an empty chapter nobody can read.
        check(content.blocks.isNotEmpty()) {
            "The chapter page at $url parsed to no readable blocks, so it is not a chapter"
        }
        return content
    }

    private fun novelUrl(id: String) = "${MatchaNovelsParsing.SITE_BASE}/novela/$id"

    private companion object {
        const val FIRST_PAGE = 1
        const val LIBRARY_URL = "${MatchaNovelsParsing.SITE_BASE}/biblioteca"
    }
}
