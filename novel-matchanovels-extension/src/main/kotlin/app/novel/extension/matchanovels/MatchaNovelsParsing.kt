package app.novel.extension.matchanovels

import app.novel.source.api.Chapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * The pure page-to-data half of [MatchaNovelsSource]: every function takes a page as a String and
 * returns plain contract data, so the shape of the site can be pinned in a JVM test without a
 * socket.
 *
 * The metadata comes from the JSON-LD `Book` block rather than from CSS selectors (decision D3):
 * the site rewrites its inline `style="..."` on every deploy, but the structured data is written
 * for machines and stays put. The prose is anchored on `<p>` — the most stable thing a
 * server-rendered page has — with the header, nav and footer chrome removed first.
 */
internal object MatchaNovelsParsing {

    const val SOURCE_ID = "matchanovels"
    const val SITE_BASE = "https://matchanovels.com"

    /** Where a cover falls back to when the card carries no usable `src` (decision D6). */
    const val COVER_FALLBACK_PREFIX = "$SITE_BASE/static/imagenes-portadas/portada-"

    /** The four JSON-LD fields worth keeping. Everything else in the block is ignored. */
    data class BookMetadata(
        val name: String?,
        val description: String?,
        val imageUrl: String?,
        val author: String?,
    )

    // ---- Catalogue --------------------------------------------------------------------------

    /**
     * Every novel on `/biblioteca` (the site lists all 56 on one page, so there is nothing to page
     * through). A card without a title or a usable slug is skipped rather than guessed at.
     */
    fun parseCatalogue(html: String): List<Novel> {
        val document = Jsoup.parse(html)
        return document.select("article[data-mn-tarjeta]").mapNotNull { card ->
            val link = card.selectFirst("a[aria-label][href]") ?: card.selectFirst("a[href]")
                ?: return@mapNotNull null
            val slug = novelSlugFromHref(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("aria-label").takeIf { it.isNotBlank() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: link.text()
            val normalizedTitle = normalize(title)
            if (normalizedTitle.isBlank()) return@mapNotNull null

            Novel(
                sourceId = SOURCE_ID,
                id = slug,
                title = normalizedTitle,
                coverUrl = coverUrl(card.selectFirst("img")?.attr("src"), slug),
            )
        }
    }

    /** The search the site does in JavaScript, done here (decision D2): a folded substring match. */
    fun filterByTitle(novels: List<Novel>, query: String): List<Novel> {
        val needle = foldForSearch(query)
        if (needle.isBlank()) return novels
        return novels.filter { foldForSearch(it.title).contains(needle) }
    }

    /** Case- and accent-insensitive (`Corazón` folds to `corazon`), because readers type both ways. */
    fun foldForSearch(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()

    // ---- Novel page -------------------------------------------------------------------------

    /** The `<h1>` gives the title; the JSON-LD `Book` gives description, cover and author. */
    fun parseNovelDetails(html: String, novel: Novel): Novel {
        val document = Jsoup.parse(html)
        val title = document.selectFirst("h1")?.let { normalize(it.text()) }?.takeIf { it.isNotBlank() }
        val book = document.select("script[type=application/ld+json]")
            .mapNotNull { parseBookJsonLd(it.data()) }
            .firstOrNull()

        return novel.copy(
            title = title ?: novel.title,
            author = book?.author ?: novel.author,
            description = book?.description ?: novel.description,
            coverUrl = book?.imageUrl ?: novel.coverUrl,
        )
    }

    /**
     * Reads the one JSON-LD block whose `@type` is `Book`. The site also emits a `BreadcrumbList`
     * block, so the type check is what keeps its `name` out of the metadata.
     */
    fun parseBookJsonLd(rawJson: String): BookMetadata? {
        if (jsonStringField(rawJson, "@type") != "Book") return null
        return BookMetadata(
            name = jsonStringField(rawJson, "name"),
            description = jsonStringField(rawJson, "description"),
            imageUrl = fieldAsText(rawJson, "image", nestedKey = "url"),
            author = fieldAsText(rawJson, "author", nestedKey = "name"),
        )
    }

    // ---- Chapters ---------------------------------------------------------------------------

    /**
     * The chapter list as a reader must see it. The novel page carries the full list in its Next.js
     * flight payload (see [parseChapterPayload]); the rendered anchors are a truncated view of the
     * very same list (measured: 25 anchors against 3216 payload rows on `Shadow-Slave`), so the
     * anchors are only used when no payload is present.
     */
    fun parseChapterList(html: String, novel: Novel): List<Chapter> =
        parseChapterPayload(html, novel) ?: parseChapterLinks(html, novel)

    /**
     * An absent payload may use legacy anchors; a present but invalid payload must fail. Decode the
     * flight string once, then parse the containing JSON object, not regex-selected fragments. Its
     * declared total and every row must agree before any chapter list can reach the host.
     */
    fun parseChapterPayload(html: String, novel: Novel): List<Chapter>? {
        val originalKey = capitulosKeyIndex(html) ?: return null
        try {
            val escaped = originalKey >= 2 && html[originalKey - 2] == '\\'
            val payload = if (escaped) {
                FLIGHT_STRING.findAll(html).mapNotNull { match ->
                    val value = MatchaNovelsJson.read(html, match.range.last + 1) as? String
                    value?.takeIf { capitulosKeyIndex(it) != null }
                }.firstOrNull() ?: error("flight string is missing")
            } else {
                html
            }
            val key = capitulosKeyIndex(payload) ?: error("chapter key is missing")
            val root = chapterObject(payload, key)
            val rows = root["capitulos"] as? List<*> ?: error("capitulos is not an array")
            val total = root["total"] as? Double ?: error("chapter total is missing or invalid")
            check(total >= 0 && total == rows.size.toDouble()) {
                "incomplete chapter list: expected $total rows, received ${rows.size}"
            }
            val slug = root["slug"] as? String ?: novel.id
            val ids = HashSet<String>()
            return rows.mapIndexed { index, value ->
                val row = value as? List<*> ?: error("chapter row $index is not an array")
                check(row.size == 5 && row[1] is String && row[2] is Boolean) {
                    "invalid chapter row $index"
                }
                val id = row[0] as? String ?: error("invalid chapter id at row $index")
                val number = id.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: error("invalid chapter number at row $index")
                check(ids.add(id)) { "duplicate chapter $id" }
                Chapter(
                    sourceId = novel.sourceId,
                    novelId = novel.id,
                    id = id,
                    title = "Capítulo $id",
                    number = number,
                    url = "$SITE_BASE/novela/$slug/capitulo/$id",
                )
            }
        } catch (failure: IllegalStateException) {
            throw IllegalStateException(
                "MatchaNovels: invalid chapter payload for ${novel.id}: ${failure.message}",
                failure,
            )
        }
    }

    /** A nested metadata object (or a brace in its string) is not the chapter object. */
    private fun chapterObject(payload: String, key: Int): Map<*, *> {
        var start = payload.lastIndexOf('{', key)
        var failure: IllegalStateException? = null
        while (start >= 0) {
            try {
                val value = MatchaNovelsJson.read(payload, start) as? Map<*, *>
                if (value?.containsKey("capitulos") == true) return value
            } catch (invalid: IllegalStateException) {
                failure = invalid
            }
            start = payload.lastIndexOf('{', start - 1)
        }
        throw IllegalStateException("chapter object is missing or malformed", failure)
    }

    /**
     * The chapter list, read from the novel page's anchors. A chapter is identified by the `{n}` in
     * `/novela/{slug}/capitulo/{n}`; `data-bloqueado`/`data-leido` are ignored because a list flag
     * alone does not establish chapter-page access. Keep the chapter and check its page when read.
     *
     * The page repeats its first chapter twice (a "start reading" call to action and the real list
     * row), so candidates for the same number are collapsed, preferring the one titled like a
     * chapter over the CTA's label.
     */
    fun parseChapterLinks(html: String, novel: Novel): List<Chapter> {
        val document = Jsoup.parse(html)
        val chapters = LinkedHashMap<String, Chapter>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href")
            if (chapterNovelSlug(href) != novel.id) continue
            val segment = chapterSegmentFromHref(href) ?: continue
            if (segment.isBlank()) continue

            val title = normalize(link.selectFirst("span")?.text() ?: link.text())
                .ifBlank { "Capítulo $segment" }
            val candidate = Chapter(
                sourceId = novel.sourceId,
                novelId = novel.id,
                id = segment,
                title = title,
                number = segment.toFloatOrNull(),
                url = absoluteUrl(href),
            )
            val existing = chapters[segment]
            if (existing == null || (looksLikeChapterTitle(title) && !looksLikeChapterTitle(existing.title))) {
                chapters[segment] = candidate
            }
        }
        return chapters.values.toList()
    }

    // ---- Flight-payload detection --------------------------------------

    /** The index of the `capitulos` key, tolerating both `"capitulos"` and `\"capitulos\"`. */
    private fun capitulosKeyIndex(html: String): Int? {
        var from = 0
        while (true) {
            val index = html.indexOf(CAPITULOS_KEY, from)
            if (index < 0) return null
            from = index + CAPITULOS_KEY.length
            if (index == 0 || html[index - 1] != '"') continue
            var cursor = index + CAPITULOS_KEY.length
            if (cursor < html.length && html[cursor] == '\\') cursor++
            if (cursor >= html.length || html[cursor] != '"') continue
            val afterQuote = skipWhitespace(html, cursor + 1)
            if (afterQuote < html.length && html[afterQuote] == ':') return index
        }
    }

    // ---- Chapter text -----------------------------------------------------------------------

    /**
     * The chapter's prose. The header, nav and footer are removed first because the site wraps its
     * whole navigation in a single `<p>`; what is left that looks like a call to action is dropped
     * by [isChapterChrome]. Text is never taken from `style`-dependent selectors.
     */
    fun parseChapterContent(html: String): ChapterContent {
        val document = Jsoup.parse(html)
        document.select(NOISE_SELECTORS).remove()
        check(document.select("h2").none { normalize(it.text()) == "Este capítulo es para miembros VIP" }) {
            "MatchaNovels: chapter requires VIP membership"
        }

        val blocks = document.select("p")
            .map { normalize(it.text()) }
            .filter { it.isNotBlank() && !isChapterChrome(it) }
            .map { ContentBlock.Paragraph(it) }

        return ChapterContent(blocks)
    }

    // ---- Small pure helpers -----------------------------------------------------------------

    /** The real image address behind `/_next/image?url=<encoded>&w=...`, or null if not proxied. */
    fun nextImageSource(src: String): String? {
        if (!src.contains(NEXT_IMAGE)) return null
        val query = src.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (pair.substring(0, separator) == "url") {
                val value = pair.substring(separator + 1)
                val address = if (value.startsWith("https://", ignoreCase = true) ||
                    value.startsWith("http://", ignoreCase = true) || value.startsWith("/")
                ) {
                    value
                } else {
                    percentDecode(value)
                }
                return address.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** The cover of a card: the decoded `_next/image` url, the raw url, or the portada fallback. */
    fun coverUrl(src: String?, slug: String): String {
        val decoded = src?.let { nextImageSource(it) ?: it.takeIf(String::isNotBlank) }
        return decoded ?: "$COVER_FALLBACK_PREFIX$slug.jpg"
    }

    /** The slug of a `/novela/{slug}` address; a chapter address (deeper path) yields null. */
    fun novelSlugFromHref(href: String): String? =
        NOVEL_PATH.matchEntire(pathOf(href))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** The novel slug of a `/novela/{slug}/capitulo/{n}` address; a novel address alone yields null. */
    fun chapterNovelSlug(href: String): String? =
        CHAPTER_PATH.matchEntire(pathOf(href))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** The `{n}` segment of a chapter address. */
    fun chapterSegmentFromHref(href: String): String? =
        CHAPTER_PATH.matchEntire(pathOf(href))?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

    /** The chapter number carried by the address, when it is a number. */
    fun chapterNumberFromHref(href: String): Float? = chapterSegmentFromHref(href)?.toFloatOrNull()

    /** A relative site address made absolute; an already-absolute one is left alone. */
    fun absoluteUrl(href: String): String = when {
        href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true) -> href
        href.startsWith("/") -> SITE_BASE + href
        else -> "$SITE_BASE/$href"
    }

    /** Whether a paragraph is site chrome rather than prose. */
    fun isChapterChrome(text: String): Boolean =
        text.lowercase() in CHROME_LABELS || READ_NOW.matches(text)

    /** Folds a non-breaking space and collapses runs of whitespace, as the fixture source does. */
    fun normalize(text: String): String = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()

    // ---- JSON scanning (four fields, no parser dependency) ----------------------------------

    /**
     * Returns the raw JSON value that follows [key], as the source text between its delimiters. A
     * key that is missing — or that is a string key but not followed by a value — yields null.
     */
    private fun jsonField(json: String, key: String): String? {
        val token = "\"$key\""
        var from = 0
        while (true) {
            val keyIndex = json.indexOf(token, from)
            if (keyIndex < 0) return null
            var cursor = skipWhitespace(json, keyIndex + token.length)
            if (cursor < json.length && json[cursor] == ':') {
                cursor = skipWhitespace(json, cursor + 1)
                readValue(json, cursor)?.let { return it }
            }
            from = keyIndex + token.length
        }
    }

    /** The value of [key] decoded as a string, or null when it is absent or not a string. */
    private fun jsonStringField(json: String, key: String): String? =
        jsonField(json, key)?.takeIf { it.startsWith("\"") }?.let(::decodeJsonString)

    /**
     * A field whose value may be either a bare string or an object carrying [nestedKey] (the site
     * writes `author` as `{"@type":"Organization","name":"MatchaNovels"}` and `image` as a string).
     */
    private fun fieldAsText(json: String, key: String, nestedKey: String): String? {
        val value = jsonField(json, key) ?: return null
        return when {
            value.startsWith("\"") -> decodeJsonString(value)
            value.startsWith("{") -> jsonStringField(value, nestedKey)
            else -> null
        }
    }

    private fun readValue(json: String, start: Int): String? {
        if (start >= json.length) return null
        return when (json[start]) {
            '"' -> stringLiteral(json, start)
            '{' -> balanced(json, start, '{', '}')
            '[' -> balanced(json, start, '[', ']')
            else -> primitive(json, start)
        }
    }

    /** The string literal starting at [start], including both quotes; null when unterminated. */
    private fun stringLiteral(json: String, start: Int): String? {
        var cursor = start + 1
        while (cursor < json.length) {
            when (json[cursor]) {
                '\\' -> cursor += 2
                '"' -> return json.substring(start, cursor + 1)
                else -> cursor++
            }
        }
        return null
    }

    /** The balanced `open`..`close` region starting at [start], ignoring braces inside strings. */
    private fun balanced(json: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var cursor = start
        while (cursor < json.length) {
            when (json[cursor]) {
                '"' -> {
                    val literal = stringLiteral(json, cursor) ?: return null
                    cursor += literal.length
                    continue
                }
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return json.substring(start, cursor + 1)
                }
            }
            cursor++
        }
        return null
    }

    /** A scalar (number/bool/null) up to the next delimiter. */
    private fun primitive(json: String, start: Int): String? {
        var cursor = start
        while (cursor < json.length && !json[cursor].isWhitespace() && json[cursor] != ',' &&
            json[cursor] != '}' && json[cursor] != ']'
        ) {
            cursor++
        }
        return json.substring(start, cursor).takeIf { it.isNotEmpty() }
    }

    private fun decodeJsonString(literal: String): String {
        val body = literal.removeSurrounding("\"")
        val out = StringBuilder(body.length)
        var cursor = 0
        while (cursor < body.length) {
            val current = body[cursor]
            if (current != '\\' || cursor + 1 >= body.length) {
                out.append(current)
                cursor++
                continue
            }
            when (val escape = body[cursor + 1]) {
                '"', '\\', '/' -> {
                    out.append(escape)
                    cursor += 2
                }
                'b' -> {
                    out.append('\b')
                    cursor += 2
                }
                'f' -> {
                    out.append('\u000C')
                    cursor += 2
                }
                'n' -> {
                    out.append('\n')
                    cursor += 2
                }
                'r' -> {
                    out.append('\r')
                    cursor += 2
                }
                't' -> {
                    out.append('\t')
                    cursor += 2
                }
                'u' -> {
                    val code = if (cursor + 6 <= body.length) {
                        body.substring(cursor + 2, cursor + 6).toIntOrNull(16)
                    } else {
                        null
                    }
                    if (code != null) {
                        out.append(code.toChar())
                        cursor += 6
                    } else {
                        out.append(escape)
                        cursor += 2
                    }
                }
                else -> {
                    out.append(escape)
                    cursor += 2
                }
            }
        }
        return out.toString()
    }

    private fun skipWhitespace(json: String, from: Int): Int {
        var cursor = from
        while (cursor < json.length && json[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun percentDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    /** The path part of an href, so an absolute chapter link matches the same patterns. */
    private fun pathOf(href: String): String {
        val path = href.substringBefore('#').substringBefore('?')
        val schemeEnd = path.indexOf("://")
        if (schemeEnd < 0) return path
        val afterHost = path.substring(schemeEnd + 3)
        val slash = afterHost.indexOf('/')
        return if (slash < 0) "" else afterHost.substring(slash)
    }

    private fun looksLikeChapterTitle(title: String) = title.startsWith("Capítulo", ignoreCase = true)

    private val FLIGHT_STRING = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*(?=")""")
    private val NOVEL_PATH = Regex("^/novela/([^/]+)/?$")
    private val CHAPTER_PATH = Regex("^/novela/([^/]+)/capitulo/([^/]+)/?$")
    private const val NEXT_IMAGE = "/_next/image"

    /** One `["<number>","<date>",...]` row of the `capitulos` array; group 1 is the chapter number. */

    private const val CAPITULOS_KEY = "capitulos"

    /** The elements that are site structure rather than chapter text. */
    private const val NOISE_SELECTORS = "script, style, nav, header, footer, aside, [data-mn-anuncio]"

    /** The labels the site's own navigation puts in a paragraph; never prose. */
    private val CHROME_LABELS = setOf(
        "entrar",
        "crear cuenta",
        "biblioteca",
        "anterior",
        "índice",
        "indice",
        "siguiente",
        "índice de la novela",
        "indice de la novela",
    )

    /** The in-reader "read now" call to action, which the site renders as a paragraph. */
    private val READ_NOW = Regex("(?i)^#?\\s*leer\\s+ahora$")

    /** `\s` does not match a non-breaking space, so it is folded to a real space first. */
    private val WHITESPACE = Regex("[\\s\\u00A0]+")

    private val DIACRITICS = Regex("\\p{Mn}+")
}
