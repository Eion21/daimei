package app.novel.extension.skynovels

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import java.net.URLEncoder

/**
 * The API-to-data half of [SkyNovelsSource]: every function takes a REST response as a String and
 * returns plain contract data, so the shape of the API can be pinned in a JVM test without a socket.
 *
 * SkyNovels is an Angular 9 client-side app, so its HTML is an empty shell and there is nothing to
 * scrape. All of the data comes from `https://api.skynovels.net/api`, which needs no auth and serves
 * no challenge, and the JSON is read by the hand-written [SkyNovelsJson] rather than a bundled
 * library.
 *
 * Three measured properties drive the parser:
 * - The chapter text carries zero-width characters (`\u200B`, `\u200C`, up to 31% of a chapter) as an
 *   anti-scraping watermark, so they are stripped before anything else.
 * - The chapter text is heterogeneous: some chapters are markdown and some are HTML, so both are
 *   accepted.
 * - `isVip` does not gate the prose, so it is surfaced but never used to drop a chapter.
 */
internal object SkyNovelsParsing {

    const val SOURCE_ID = "skynovels"

    /** The REST API every call goes through. */
    const val API_BASE = "https://api.skynovels.net/api"

    /** The reader-facing site, kept for reference; the extension never fetches its HTML. */
    const val SITE_BASE = "https://www.skynovels.net"

    /** The search parameter is `q`; `?search=` returns the unfiltered list and is never used. */
    private const val SEARCH_PARAM = "q"

    /** Appended to a chapter title whose `isVip` is set, because the contract has no vip field. */
    private const val VIP_LABEL = "VIP"

    // ---- URLs -------------------------------------------------------------------------------

    fun catalogueUrl(page: Int): String = "$API_BASE/novels?page=$page"

    fun searchUrl(query: String, page: Int): String =
        "$API_BASE/novels?$SEARCH_PARAM=${encode(query)}&page=$page"

    fun baseUrl(novelId: String): String = "$API_BASE/novels/$novelId/base"

    fun genresUrl(novelId: String): String = "$API_BASE/novels/$novelId/genres"

    fun chapterListUrl(novelId: String): String = "$API_BASE/novel-chapters/$novelId"

    /** The chapter id is global across the site, so the content address needs nothing but the id. */
    fun chapterUrl(chapterId: String): String = "$API_BASE/chapters/$chapterId"

    /** The cover address (decision D7); the `image` field is the bare `.webp` file name. */
    fun coverUrl(image: String?): String? {
        val name = image?.trim().orEmpty()
        return if (name.isEmpty()) null else "$API_BASE/get-image/$name/novels/true"
    }

    // ---- Catalogue --------------------------------------------------------------------------

    /**
     * A catalogue or search response: `{novels:[...], page, limit, total, totalPages}`.
     *
     * A card without an id or a title is skipped rather than guessed at. The next page is reported
     * from the API's own `page`/`totalPages`, so pagination is the server's numbers, not a guess from
     * the count of rows.
     */
    fun parseCatalogue(json: String): NovelPage {
        val root = SkyNovelsJson.asObject(SkyNovelsJson.parse(json)) ?: return NovelPage(emptyList(), false)
        val novels = SkyNovelsJson.asArray(root["novels"]).orEmpty().mapNotNull(::parseNovelCard)
        val page = SkyNovelsJson.asInt(root["page"])
        val totalPages = SkyNovelsJson.asInt(root["totalPages"])
        val hasNextPage = page != null && totalPages != null && page < totalPages
        return NovelPage(novels, hasNextPage)
    }

    private fun parseNovelCard(value: Any?): Novel? {
        val card = SkyNovelsJson.asObject(value) ?: return null
        val id = idText(card["id"]) ?: return null
        val title = card.text("nvl_title")?.let(::normalize)?.takeIf { it.isNotBlank() } ?: return null
        return Novel(
            sourceId = SOURCE_ID,
            id = id,
            title = title,
            author = card.text("nvl_writer")?.let(::normalize)?.takeIf { it.isNotBlank() },
            description = card.text("nvl_content")?.let(::cleanSynopsis)?.takeIf { it.isNotBlank() },
            coverUrl = coverUrl(card.text("image")),
        )
    }

    // ---- Novel detail -----------------------------------------------------------------------

    /**
     * The novel as `/api/novels/{id}/base` describes it. The response shape is not documented, so the
     * novel object is accepted either at the top level or under a `novel` key (object or one-element
     * array), and every field falls back to what the caller already had so a partial response never
     * blanks a loaded novel.
     */
    fun parseNovelDetails(json: String, novel: Novel): Novel {
        val root = SkyNovelsJson.asObject(SkyNovelsJson.parse(json)) ?: return novel
        val fields = novelFieldsIn(root) ?: return novel
        return novel.copy(
            title = fields.text("nvl_title")?.let(::normalize)?.takeIf { it.isNotBlank() } ?: novel.title,
            author = fields.text("nvl_writer")?.let(::normalize)?.takeIf { it.isNotBlank() } ?: novel.author,
            description = fields.text("nvl_content")?.let(::cleanSynopsis)?.takeIf { it.isNotBlank() }
                ?: novel.description,
            coverUrl = coverUrl(fields.text("image")) ?: novel.coverUrl,
        )
    }

    /** The genres a novel belongs to, from `{genres:[{id, genre_name}]}`. */
    fun parseGenres(json: String): List<String> {
        val root = SkyNovelsJson.asObject(SkyNovelsJson.parse(json)) ?: return emptyList()
        return SkyNovelsJson.asArray(root["genres"]).orEmpty().mapNotNull { entry ->
            SkyNovelsJson.asObject(entry)?.text("genre_name")?.let(::normalize)?.takeIf { it.isNotBlank() }
        }
    }

    // ---- Chapter list -----------------------------------------------------------------------

    /**
     * The complete chapter list from `/api/novel-chapters/{id}` — `{novel:[{..., chapters:[...]}]}`.
     *
     * The API returns every chapter (measured: 7190/7190, 6684/6684, 4034/4034, 3309/3309), so unlike
     * MatchaNovels there is no truncated view to work around. The declared `nvl_chapters` is still
     * checked against what came back: a shorter list is a truncation the reader must hear about, not
     * a chapter list to quietly hand back. `isVip` is kept as data and never drops a chapter (H1).
     */
    fun parseChapterList(json: String, novel: Novel): List<Chapter> {
        val root = SkyNovelsJson.asObject(SkyNovelsJson.parse(json)) ?: return emptyList()
        val container = novelFieldsIn(root) ?: root
        val chapters = SkyNovelsJson.asArray(container["chapters"]).orEmpty()
            .mapNotNull { parseChapterRow(it, novel) }
        verifyChapterCount(SkyNovelsJson.asInt(container["nvl_chapters"]), chapters.size)
        return chapters
    }

    /**
     * Fails loudly when the API declares a chapter total the response does not contain. A mismatch is
     * the exact failure mode of the MatchaNovels bug (25 chapters reported where 3216 existed), so it
     * must never pass silently. A response without a declared total is left alone.
     */
    fun verifyChapterCount(declared: Int?, actual: Int) {
        if (declared == null) return
        check(declared == actual) {
            "The API declared $declared chapters but returned $actual; " +
                "a truncated list must not be reported as complete"
        }
    }

    private fun parseChapterRow(value: Any?, novel: Novel): Chapter? {
        val row = SkyNovelsJson.asObject(value) ?: return null
        val id = idText(row["id"]) ?: return null
        val number = SkyNovelsJson.asFloat(row["chp_number"])
        val baseTitle = row.text("chp_index_title")?.let(::normalize)?.takeIf { it.isNotBlank() }
            ?: number?.let { "Capítulo ${it.toInt()}" }
            ?: "Capítulo $id"
        // The contract has no vip field, so `isVip` is surfaced as a title label. It never drops a
        // chapter: a VIP chapter serves its prose like any other (H1).
        val title = if (isVip(row)) "$baseTitle · $VIP_LABEL" else baseTitle
        return Chapter(
            sourceId = SOURCE_ID,
            novelId = novel.id,
            id = id,
            title = title,
            number = number,
        )
    }

    /** `isVip` arrives as `"0"`/`"1"`, and is accepted as a number or a boolean too. */
    private fun isVip(row: Map<String, Any?>): Boolean = when (val value = row["isVip"]) {
        is String -> value.trim() == "1" || value.equals("true", ignoreCase = true)
        is Double -> value != 0.0
        is Boolean -> value
        else -> false
    }

    // ---- Chapter text -----------------------------------------------------------------------

    /** The chapter's prose from `/api/chapters/{id}` — `{chapter:{chp_content, ...}}`. */
    fun parseChapterContent(json: String): ChapterContent {
        val root = SkyNovelsJson.asObject(SkyNovelsJson.parse(json)) ?: return ChapterContent(emptyList())
        val chapter = SkyNovelsJson.asObject(root["chapter"]) ?: return ChapterContent(emptyList())
        val raw = chapter.text("chp_content") ?: return ChapterContent(emptyList())
        return ChapterContent(parseChapterText(raw))
    }

    /**
     * The chapter text as blocks, in the order it reads.
     *
     * The zero-width watermark is removed first (H2), because it is seeded *between* characters and
     * would otherwise reach the reader and break search and highlighting. The cleaned text is then
     * read as HTML when it carries tags and as markdown otherwise (H3), and the HTML form is
     * normalised into the markdown form so both go through one reader.
     */
    fun parseChapterText(raw: String): List<ContentBlock> {
        val visible = stripInvisible(raw)
        val markdown = if (looksLikeHtml(visible)) markdownFromHtml(visible) else visible
        return parseMarkdown(markdown)
    }

    /**
     * Removes the zero-width/format characters the site seeds into `chp_content`.
     *
     * The check is the whole `Cf` (format) category, not the two codepoints that happen to be there
     * today: the watermark is a technique and the site can rotate which invisible it uses. `\n`, `\t`
     * and `\r` are `Cc` and are never touched, so paragraph structure survives.
     */
    fun stripInvisible(text: String): String {
        if (text.none { isInvisible(it) }) return text
        val out = StringBuilder(text.length)
        for (char in text) {
            if (!isInvisible(char)) out.append(char)
        }
        return out.toString()
    }

    private fun isInvisible(char: Char): Boolean = Character.getType(char) == Character.FORMAT.toInt()

    /** The same four fields the fixture JSON-LD and the API agree on: title, author, description, cover. */
    private fun novelFieldsIn(root: Map<String, Any?>): Map<String, Any?>? {
        val value = root["novel"] ?: return root.takeIf { it.containsKey("chapters") || it.containsKey("nvl_title") }
        return SkyNovelsJson.asObject(value)
            ?: SkyNovelsJson.asArray(value)?.firstNotNullOfOrNull(SkyNovelsJson::asObject)
    }

    // ---- Markdown ---------------------------------------------------------------------------

    /**
     * Reads markdown into blocks. The reader renders a paragraph's text verbatim, with no markdown
     * of its own, so structure that markdown encodes is turned into [ContentBlock]s here and the
     * inline emphasis markers are stripped rather than shown as literal `**`.
     */
    private fun parseMarkdown(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val title = stripInline(heading.groupValues[2])
                if (title.isNotBlank()) blocks += ContentBlock.Heading(title, level)
                continue
            }
            if (HR.matches(line)) {
                blocks += ContentBlock.Divider
                continue
            }
            val image = IMAGE_ONLY.matchEntire(line)
            if (image != null) {
                val url = image.groupValues[2].trim()
                if (url.isNotBlank()) {
                    blocks += ContentBlock.Image(url, image.groupValues[1].trim().takeIf { it.isNotBlank() })
                }
                continue
            }

            val unquoted = line.removePrefix(">").trim()
            val listItem = LIST_ITEM.matchEntire(unquoted)
            val prose = stripInline(listItem?.groupValues?.get(2) ?: unquoted)
            if (prose.isNotBlank()) blocks += ContentBlock.Paragraph(prose)
        }
        return blocks
    }

    /** Drops the inline markdown the reader would otherwise show literally. */
    private fun stripInline(text: String): String {
        var out = IMAGE_INLINE.replace(text, "$1")
        out = LINK.replace(out, "$1")
        out = out.replace("**", "").replace("__", "").replace("~~", "")
        out = out.replace("*", "").replace("`", "")
        return normalize(out)
    }

    /**
     * Normalises the HTML variant of a chapter into the markdown the [parseMarkdown] reader accepts:
     * block boundaries become newlines, headings become `#`, images become `![alt](src)`, list items
     * become `-`, and the remaining tags are removed and their entities decoded. Because the markdown
     * reader runs afterwards, a chapter that mixes both forms is read correctly too.
     */
    private fun markdownFromHtml(html: String): String {
        var out = HTML_BR.replace(html, "\n")
        out = HTML_HR.replace(out, "\n---\n")
        out = HTML_HEADING.replace(out) { match ->
            "\n" + "#".repeat(match.groupValues[1].toInt()) + " " + match.groupValues[2] + "\n"
        }
        out = HTML_IMAGE.replace(out) { match ->
            val tag = match.value
            val src = HTML_SRC.find(tag)?.groupValues?.get(1).orEmpty()
            if (src.isBlank()) {
                ""
            } else {
                val alt = HTML_ALT.find(tag)?.groupValues?.get(1).orEmpty()
                "![$alt]($src)"
            }
        }
        out = HTML_LIST_ITEM.replace(out, "\n- ")
        out = HTML_BLOCK_BOUNDARY.replace(out, "\n")
        out = TAG.replace(out, "")
        return decodeEntities(out)
    }

    private fun looksLikeHtml(text: String): Boolean = HTML_TAG.containsMatchIn(text)

    // ---- Small pure helpers -----------------------------------------------------------------

    /** Folds non-breaking spaces and collapses runs of whitespace, as the fixture source does. */
    fun normalize(text: String): String = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()

    /** A synopsis may be plain, HTML or markdown; it is reduced to one readable line. */
    private fun cleanSynopsis(text: String): String {
        var out = HTML_BREAK.replace(stripInvisible(text), " ")
        out = HTML_BLOCK_BOUNDARY.replace(out, " ")
        out = TAG.replace(out, "")
        return normalize(decodeEntities(out))
    }

    /** A display id from a JSON scalar that may be a number or a string, as the API mixes both. */
    private fun idText(value: Any?): String? = when (value) {
        is Double -> value.toLong().toString()
        is String -> value.trim().takeIf { it.isNotBlank() }
        else -> null
    }

    private fun Map<String, Any?>.text(key: String): String? = SkyNovelsJson.asString(this[key])

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Decodes the named and numeric HTML entities the site's HTML variant can carry. */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '&') {
                out.append(char)
                index++
                continue
            }
            val semicolon = text.indexOf(';', index + 1)
            val decoded = if (semicolon in (index + 1)..(index + MAX_ENTITY_LENGTH)) {
                val name = text.substring(index + 1, semicolon)
                NAMED_ENTITIES[name] ?: numericEntity(name)
            } else {
                null
            }
            if (decoded == null) {
                out.append(char)
                index++
            } else {
                out.append(decoded)
                index = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun numericEntity(name: String): String? {
        val code = when {
            name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> null
        } ?: return null
        if (code !in 0..MAX_CODE_POINT) return null
        return String(Character.toChars(code))
    }

    // ---- Patterns ---------------------------------------------------------------------------

    private const val MAX_ENTITY_LENGTH = 10
    private const val MAX_CODE_POINT = 0x10FFFF

    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val HR = Regex("^([-*_])(?:\\s*\\1){2,}$")
    private val IMAGE_ONLY = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$")
    private val LIST_ITEM = Regex("^([-*+]|\\d+\\.)\\s+(.+)$")
    private val IMAGE_INLINE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")

    /** Matches an opening or closing HTML tag whose name starts with a letter; `< 3` is not one. */
    private val HTML_TAG = Regex("(?i)</?[a-z][a-z0-9]*\\b[^<>]*>")
    private val HTML_BR = Regex("(?i)<br\\s*/?\\s*>")
    private val HTML_HR = Regex("(?i)<hr\\s*/?\\s*>")
    private val HTML_BREAK = Regex("(?i)<(?:br|hr)\\b[^>]*>")
    private val HTML_HEADING = Regex("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>")
    private val HTML_IMAGE = Regex("(?i)<img\\b[^>]*>")
    private val HTML_SRC = Regex("(?i)\\bsrc\\s*=\\s*[\"']?([^\"'>\\s]+)")
    private val HTML_ALT = Regex("(?i)\\balt\\s*=\\s*\"([^\"]*)\"")
    private val HTML_LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
    private val HTML_BLOCK_BOUNDARY =
        Regex("(?i)</?(?:p|div|blockquote|section|article|table|tr|ul|ol|dl|dt|dd|figure|figcaption|h[1-6])\\b[^>]*>")
    private val TAG = Regex("<[^>]*>")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "laquo" to "«",
        "raquo" to "»",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "middot" to "·",
        "deg" to "°",
        "times" to "×",
        "iquest" to "¿",
        "iexcl" to "¡",
    )
}
