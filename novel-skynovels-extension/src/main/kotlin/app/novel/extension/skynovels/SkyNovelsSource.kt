package app.novel.extension.skynovels

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo

/**
 * A real novel source for `https://www.skynovels.net/`, the second after MatchaNovels.
 *
 * The site is an Angular 9 client-side application, so its HTML is an empty shell with no novels in
 * it. Everything the reader needs comes from the site's own REST API, `https://api.skynovels.net/api`,
 * which needs no auth and serves no challenge. This source therefore calls the API rather than
 * scraping a document.
 *
 * It is a [NetworkedNovelSource]: it ships no HTTP client and never touches a dispatcher. It asks the
 * host's [NovelNetwork] for a response and hands the String to [SkyNovelsParsing], which owns the
 * site-specific parsing. The host owns the socket, the timeouts, the `User-Agent` and the
 * main-thread offload.
 *
 * Every method is a thin fetch-then-parse: the interesting decisions live in the parsing object, where
 * they can be pinned by a pure JVM test.
 */
class SkyNovelsSource : NetworkedNovelSource {

    /**
     * Injected through [bindNetwork] before the host registers this source. `lateinit` rather than a
     * nullable field so a source reached before binding fails loudly instead of silently fetching
     * nothing.
     */
    private lateinit var network: NovelNetwork

    override val info = SourceInfo(
        id = SkyNovelsParsing.SOURCE_ID,
        name = "SkyNovels",
        version = "1.0.0",
        language = "es",
    )

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    /** The whole catalogue is paged by the API, so any page the host asks for is a real page. */
    override suspend fun popular(page: Int): NovelPage {
        require(page >= FIRST_PAGE) { "The catalogue starts at page $FIRST_PAGE, but page $page was asked for" }
        return SkyNovelsParsing.parseCatalogue(network.fetch(SkyNovelsParsing.catalogueUrl(page)))
    }

    /** Search is the API's own `?q=`, paged like the catalogue (the parameter is not `search`). */
    override suspend fun search(query: String, page: Int): NovelPage {
        require(page >= FIRST_PAGE) { "Search starts at page $FIRST_PAGE, but page $page was asked for" }
        return SkyNovelsParsing.parseCatalogue(network.fetch(SkyNovelsParsing.searchUrl(query, page)))
    }

    override suspend fun getNovelDetails(novel: Novel): Novel =
        SkyNovelsParsing.parseNovelDetails(network.fetch(SkyNovelsParsing.baseUrl(novel.id)), novel)

    override suspend fun getChapters(novel: Novel): List<Chapter> =
        SkyNovelsParsing.parseChapterList(network.fetch(SkyNovelsParsing.chapterListUrl(novel.id)), novel)

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val content = SkyNovelsParsing.parseChapterContent(network.fetch(SkyNovelsParsing.chapterUrl(chapter.id)))
        // Zero blocks is a statement about the response, not about the chapter, so it is reported as
        // a failure rather than passed on as an empty chapter nobody can read.
        check(content.blocks.isNotEmpty()) {
            "The chapter ${chapter.id} parsed to no readable blocks, so it is not a chapter"
        }
        return content
    }

    private companion object {
        const val FIRST_PAGE = 1
    }
}
