package app.novel.source.http

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The id this module registers under. A parsed [Novel] is stamped with it because
 * [parseNovelList] takes no novel to copy the id from.
 */
internal const val SOURCE_ID = "http-fixtures"

/**
 * Every element removed before a chapter's text is read.
 *
 * The ad block is listed alongside `script` and `style` on purpose: all three leak reader
 * content, and scoping alone does not catch them because a real page puts them *inside* the
 * article body.
 */
private const val NOISE = "script, style, .ad-slot"

/** The shapes that become [ContentBlock]s, walked in document order. */
private const val BLOCKS = "h1, h2, h3, h4, h5, h6, p, hr, img"

/** `\s` does not match a non-breaking space, so it is folded to a real space before collapsing. */
private val WHITESPACE = Regex("[\\s\\u00A0]+")

/**
 * Reads the novels of a listing or search page.
 *
 * Every selector is anchored at `#novel-list`, which is the container the fixture layout puts
 * real cards in. That anchor is load bearing: the fixture's ad block and its recommendation
 * sidebar both clone the card markup, including the `.novel-card` class itself, so a selector
 * like `.novel-card` alone would return sponsored and duplicate entries.
 *
 * hrefs are recorded exactly as the document wrote them, relative ones included. Resolving them
 * against a deployment is [HttpNovelSource]'s job, because only it knows the base url.
 *
 * Returns an empty page for an empty document, for one with no `#novel-list`, and for one Jsoup
 * has to recover from. Never throws: a source that crashes on an unexpected page takes the reader
 * down with it.
 */
fun parseNovelList(html: String): NovelPage {
    val document = Jsoup.parse(html)

    val novels = document.select("#novel-list > .novel-card").mapNotNull { card ->
        val href = card.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
        val id = idFromHref(href) ?: return@mapNotNull null
        val title = card.selectFirst(".novel-title")?.let { normalize(it.text()) }
        // A card with no readable title would render as a blank row, so it is dropped the same
        // way a titleless chapter link is.
        if (title.isNullOrBlank()) return@mapNotNull null

        Novel(
            sourceId = SOURCE_ID,
            id = id,
            title = title,
            author = card.selectFirst(".novel-author")?.let { normalize(it.text()) }?.takeIf { it.isNotBlank() },
            coverUrl = card.selectFirst("img.novel-cover")?.attr("src")?.takeIf { it.isNotBlank() },
        )
    }

    return NovelPage(novels, hasNextPage = document.selectFirst(".pagination .next-page") != null)
}

/**
 * Fills in the detail page's description, cover, title and author.
 *
 * The page is read through the `.novel-detail` article, so the ad block and sidebar that sit
 * beside it are out of scope. Every field falls back to what the caller already had, so a partial
 * or unrecognised page degrades to the listing data instead of blanking it.
 *
 * Identity is never read from the document: [novel] owns its own id and source id, and the
 * fixture's `data-novel-id` is decoration.
 */
fun parseNovelDetails(html: String, novel: Novel): Novel {
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
 * Reads the chapter links of a detail page.
 *
 * Scoped to `.novel-detail .chapter-list` because the fixture's ad block ships a chapter list of
 * its own, pointing at a chapter that does not exist. Reading `ul.chapter-list` unscoped would
 * offer the reader a chapter that 404s.
 *
 * [Chapter.url] is the raw href, relative ones included, because resolving it needs the address
 * of the document it was found on. [HttpNovelSource.getChapters] resolves them.
 *
 * The chapter number is read off the trailing segment of the slug, so a chapter called
 * `prologo` gets a null number rather than a wrong one. That heuristic is good enough for a site
 * whose slugs end in the chapter index and wrong for one whose slugs end in a year; a site that
 * disagrees has to put the number in the document.
 */
fun parseChapterList(html: String, novel: Novel): List<Chapter> {
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
 * Turns a chapter page into blocks, in the order they appear in the document.
 *
 * Reads only `.chapter-content`, so the chapter title above it and the sidebar below it never
 * reach the reader, and removes [NOISE] before walking anything, so an inline `<script>` or an ad
 * block sitting inside the article cannot become a paragraph.
 *
 * Whitespace-only paragraphs are dropped: a `&nbsp;` spacer is not prose.
 */
fun parseChapterContent(html: String, chapter: Chapter): ChapterContent {
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

/**
 * The document name a link addresses, without its extension.
 *
 * A link that addresses no document (an empty href, a bare directory) yields null and its card
 * is dropped, because a novel the source cannot reopen is worse than a novel missing from the
 * listing. Assumes same-site links, which is what the fixture emits.
 */
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

private fun Element.textOrNull(selector: String) = selectFirst(selector)?.let {
    normalize(it.text())
}?.takeIf { it.isNotBlank() }

private fun Element.alt() = attr("alt").takeIf { it.isNotBlank() }

private fun normalize(text: String) = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()
