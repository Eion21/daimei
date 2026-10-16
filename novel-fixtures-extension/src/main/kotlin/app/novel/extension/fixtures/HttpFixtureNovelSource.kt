package app.novel.extension.fixtures

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.NetworkedNovelSource
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import app.novel.source.api.NovelPage
import app.novel.source.api.SourceInfo
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * A [NetworkedNovelSource] that really fetches over HTTP, using the network the host injects.
 *
 * It is the point of design B: the extension ships no HTTP client and never touches a dispatcher. It
 * asks [NovelNetwork] for a page and turns the HTML into blocks itself, because the selectors are the
 * one part of this that is genuinely about the site. The host owns the socket, the timeouts, the
 * `User-Agent` and the main-thread offload.
 *
 * It is pointed at the fixture server from `tools/serve-fixtures.sh`
 * (`novel-http-source/src/test/resources/fixtures`), served on the host machine as
 * `http://10.0.2.2:8080/` from an emulator. The page shapes it understands are the fixture's; this is
 * a test source, not a scraper for a real site.
 *
 * The `sourceId` is distinct from the in-app `HttpNovelSource` (`http-fixtures`) so both can be
 * registered at once without their ids colliding.
 */
class HttpFixtureNovelSource : NetworkedNovelSource {

    /**
     * Injected through [bindNetwork] before the host registers this source. `lateinit` rather than a
     * nullable field so a source reached before binding fails loudly instead of silently fetching
     * nothing.
     */
    private lateinit var network: NovelNetwork

    /** Resolved once, so a malformed base address fails at construction rather than on the first fetch. */
    private val base = URI(BASE_URL)

    override val info = SourceInfo(
        id = "http-fixture-extension",
        name = "HTTP fixtures (extension)",
        version = "1.0.0",
        language = "es",
    )

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    override suspend fun popular(page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)
        return parseNovelList(network.fetch(resolve(LISTING))).withAbsoluteCovers()
    }

    override suspend fun search(query: String, page: Int): NovelPage {
        if (page != FIRST_PAGE) return NovelPage(emptyList(), hasNextPage = false)
        val url = resolve("$SEARCH?q=${URLEncoder.encode(query, "UTF-8")}")
        return parseNovelList(network.fetch(url)).withAbsoluteCovers()
    }

    override suspend fun getNovelDetails(novel: Novel): Novel {
        val html = network.fetch(detailUrl(novel.id))
        return parseNovelDetails(html, novel).withAbsoluteCover()
    }

    override suspend fun getChapters(novel: Novel): List<Chapter> {
        val url = detailUrl(novel.id)
        val html = network.fetch(url)
        return parseChapterList(html, novel).mapNotNull { chapter ->
            val href = chapter.url ?: return@mapNotNull null
            chapter.copy(url = resolveAgainst(url, href))
        }
    }

    override suspend fun getChapterContent(chapter: Chapter): ChapterContent {
        val url = chapter.url
            ?: throw IllegalStateException("Chapter ${chapter.id} carries no url, so its page cannot be read")
        val content = parseChapterContent(network.fetch(url))
        // Zero blocks is a statement about the document, not about the chapter, so it is reported as a
        // failure rather than passed on as an empty chapter nobody can read (the Phase 7 bug).
        check(content.blocks.isNotEmpty()) {
            "The chapter page at $url parsed to no readable blocks, so it is not a chapter"
        }
        return content
    }

    private fun detailUrl(novelId: String) = resolve("$DETAIL_DIRECTORY/$novelId.html")

    /** An address relative to the fixture server resolved against [BASE_URL]. */
    private fun resolve(path: String) = base.resolve(path).toString()

    /** A relative href resolved against the page it was found on, so chapter links come back absolute. */
    private fun resolveAgainst(pageUrl: String, href: String) = URI(pageUrl).resolve(href).toString()

    private fun NovelPage.withAbsoluteCovers() = copy(novels = novels.map { it.withAbsoluteCover() })

    private fun Novel.withAbsoluteCover() = copy(coverUrl = coverUrl?.let(::resolve))

    /**
     * Reads the cards inside `#novel-list` and nothing else. The fixture's ad block and sidebar clone
     * the same `.novel-card` markup, so the `#novel-list` anchor is what keeps them out, not luck.
     */
    private fun parseNovelList(html: String): NovelPage {
        val document = Jsoup.parse(html)
        val novels = document.select("#novel-list > .novel-card").mapNotNull { card ->
            val href = card.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val id = idFromHref(href) ?: return@mapNotNull null
            val title = card.selectFirst(".novel-title")?.let { normalize(it.text()) }
            if (title.isNullOrBlank()) return@mapNotNull null

            Novel(
                sourceId = info.id,
                id = id,
                title = title,
                author = card.selectFirst(".novel-author")?.let { normalize(it.text()) }?.takeIf { it.isNotBlank() },
                coverUrl = card.selectFirst("img.novel-cover")?.attr("src")?.takeIf { it.isNotBlank() },
            )
        }
        return NovelPage(novels, hasNextPage = document.selectFirst(".pagination .next-page") != null)
    }

    /** Every field falls back to what the caller already had, so a partial page never blanks a novel. */
    private fun parseNovelDetails(html: String, novel: Novel): Novel {
        val detail = Jsoup.parse(html).selectFirst(".novel-detail") ?: return novel
        return novel.copy(
            title = detail.textOrNull(".novel-title") ?: novel.title,
            author = detail.textOrNull(".novel-author") ?: novel.author,
            description = detail.textOrNull(".novel-description") ?: novel.description,
            coverUrl = detail.selectFirst(".novel-cover img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: novel.coverUrl,
        )
    }

    /**
     * Scoped to `.novel-detail .chapter-list` because the fixture's ad block ships a chapter list of
     * its own, pointing at a chapter that does not exist. The raw href is kept here; [getChapters]
     * resolves it against the page it was found on.
     */
    private fun parseChapterList(html: String, novel: Novel): List<Chapter> {
        val detail = Jsoup.parse(html).selectFirst(".novel-detail") ?: return emptyList()
        return detail.select(".chapter-list a[href]").mapNotNull { link ->
            val href = link.attr("href")
            val id = idFromHref(href) ?: return@mapNotNull null
            val title = normalize(link.text())
            if (title.isBlank()) return@mapNotNull null

            Chapter(
                sourceId = novel.sourceId,
                novelId = novel.id,
                id = id,
                title = title,
                number = numberFromId(id),
                url = href,
            )
        }
    }

    /**
     * Turns `.chapter-content` into blocks in document order. [NOISE] is removed before anything is
     * walked because the fixture puts the script, the style and the ad block *inside* the article:
     * scoping alone does not catch them.
     */
    private fun parseChapterContent(html: String): ChapterContent {
        val content = Jsoup.parse(html).selectFirst(".chapter-content") ?: return ChapterContent(emptyList())
        content.select(NOISE).remove()

        val blocks = content.select(BLOCKS).mapNotNull { element ->
            when (element.tagName()) {
                "hr" -> ContentBlock.Divider
                "img" -> element.attr("src").takeIf { it.isNotBlank() }?.let { ContentBlock.Image(it, element.alt()) }
                "p" -> normalize(element.text()).takeIf { it.isNotBlank() }?.let { ContentBlock.Paragraph(it) }
                else -> if (element.tagName().first() == 'h') {
                    normalize(element.text()).takeIf { it.isNotBlank() }?.let {
                        ContentBlock.Heading(it, headingLevel(element))
                    }
                } else {
                    null
                }
            }
        }
        return ChapterContent(blocks)
    }

    /** The document name a link addresses, without its extension. A link with no document yields null. */
    private fun idFromHref(href: String): String? {
        val path = href.substringBefore('#').substringBefore('?').trim()
        if (path.isEmpty() || path.endsWith('/')) return null
        val fileName = path.substringAfterLast('/')
        return fileName.substringBeforeLast('.', fileName).takeIf { it.isNotBlank() }
    }

    private fun numberFromId(id: String): Float? =
        id.substringAfterLast('-')
            .takeIf { segment -> segment.isNotEmpty() && segment.all(Char::isDigit) }
            ?.toFloatOrNull()

    private fun headingLevel(element: Element) =
        element.tagName().removePrefix("h").toIntOrNull()?.coerceIn(1, 6) ?: 2

    private fun Element.textOrNull(selector: String) = selectFirst(selector)?.let { normalize(it.text()) }
        ?.takeIf { it.isNotBlank() }

    private fun Element.alt() = attr("alt").takeIf { it.isNotBlank() }

    private fun normalize(text: String) = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()

    private companion object {
        const val FIRST_PAGE = 1

        /** The host machine as seen from an Android emulator; see `tools/serve-fixtures.sh`. */
        const val BASE_URL = "http://10.0.2.2:8080/"
        const val LISTING = "index.html"
        const val SEARCH = "search.html"
        const val DETAIL_DIRECTORY = "novel"

        const val NOISE = "script, style, .ad-slot"
        const val BLOCKS = "h1, h2, h3, h4, h5, h6, p, hr, img"

        /** `\s` does not match a non-breaking space, so it is folded to a real space first. */
        val WHITESPACE = Regex("[\\s\\u00A0]+")
    }
}
