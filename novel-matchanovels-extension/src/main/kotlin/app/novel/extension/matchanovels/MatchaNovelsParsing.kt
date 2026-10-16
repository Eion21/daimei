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
     * The full chapter list, read from the `capitulos` array of the page's flight payload. The
     * array holds one row per chapter — `["1","22 jul 26",false,null,null]` — and, unlike the
     * rendered anchors, is not truncated. Rows whose first field is not a number are skipped rather
     * than guessed at.
     *
     * The key is accepted in both the plain and the backslash-escaped form, because the payload is
     * embedded inside a JavaScript string literal and the served document escapes its quotes:
     * `\"capitulos\":[[\"1\",...`. The chapter `url` uses the payload's own `slug` when it carries
     * one, so a page reached by a stale id still links to its real chapters. Returns null when the
     * page carries no `capitulos` array, so the caller can fall back to the anchors.
     */
    fun parseChapterPayload(html: String, novel: Novel): List<Chapter>? {
        val keyIndex = capitulosKeyIndex(html) ?: return null
        val arrayStart = capitulosArrayStart(html, keyIndex) ?: return null
        val array = readEscapedBalanced(html, arrayStart) ?: return null
        val slug = slugBeforeKey(html, keyIndex) ?: novel.id
        return CHAPTER_ROW.findAll(array).mapNotNull { row ->
            val id = row.groupValues[1]
            val number = id.toFloatOrNull() ?: return@mapNotNull null
            Chapter(
                sourceId = novel.sourceId,
                novelId = novel.id,
                id = id,
                title = "Capítulo $id",
                number = number,
                url = "$SITE_BASE/novela/$slug/capitulo/$id",
            )
        }.toList()
    }

    /**
     * The chapter list, read from the novel page's anchors. A chapter is identified by the `{n}` in
     * `/novela/{slug}/capitulo/{n}`; `data-bloqueado`/`data-leido` are ignored on purpose — the VIP
     * tag does not gate the HTML (decision and measurement), so it must not drop a chapter here.
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

    // ---- Flight-payload scanning (no parser dependency) --------------------------------------

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

    /** The index of the `[` opening the `capitulos` array that follows the key at [keyIndex]. */
    private fun capitulosArrayStart(html: String, keyIndex: Int): Int? {
        var cursor = keyIndex + CAPITULOS_KEY.length
        if (cursor < html.length && html[cursor] == '\\') cursor++
        if (cursor >= html.length || html[cursor] != '"') return null
        cursor = skipWhitespace(html, cursor + 1)
        if (cursor >= html.length || html[cursor] != ':') return null
        cursor = skipWhitespace(html, cursor + 1)
        if (cursor < html.length && html[cursor] == '\\') cursor++
        return cursor.takeIf { it < html.length && html[it] == '[' }
    }

    /**
     * The balanced `[`..`]` region starting at [start], tolerant of the backslash-escaped quotes the
     * flight payload is embedded with: here `\"` is a string delimiter, and quoted regions are
     * skipped so a bracket inside a string can never unbalance the scan.
     */
    private fun readEscapedBalanced(html: String, start: Int): String? {
        var depth = 0
        var cursor = start
        while (cursor < html.length) {
            when {
                html[cursor] == '"' -> {
                    cursor = skipEscapedString(html, cursor + 1)
                    continue
                }
                html[cursor] == '\\' && cursor + 1 < html.length && html[cursor + 1] == '"' -> {
                    cursor = skipEscapedString(html, cursor + 2)
                    continue
                }
                html[cursor] == '[' -> depth++
                html[cursor] == ']' -> {
                    depth--
                    if (depth == 0) return html.substring(start, cursor + 1)
                }
            }
            cursor++
        }
        return null
    }

    /** The index just after the closing delimiter (`"` or `\"`) of the string opened before [from]. */
    private fun skipEscapedString(html: String, from: Int): Int {
        var cursor = from
        while (cursor < html.length) {
            val current = html[cursor]
            when {
                current == '"' -> return cursor + 1
                current == '\\' && cursor + 1 < html.length && html[cursor + 1] == '"' -> return cursor + 2
                current == '\\' && cursor + 1 < html.length -> cursor += 2
                else -> cursor++
            }
        }
        return html.length
    }

    /** The string value of the `slug` field that precedes the key at [before], if the payload has one. */
    private fun slugBeforeKey(html: String, before: Int): String? {
        var searchEnd = before
        while (searchEnd > 0) {
            val index = html.lastIndexOf(SLUG_KEY, searchEnd - 1)
            if (index < 0) return null
            searchEnd = index
            if (index == 0 || html[index - 1] != '"') continue
            var cursor = index + SLUG_KEY.length
            if (cursor < html.length && html[cursor] == '\\') cursor++
            if (cursor >= html.length || html[cursor] != '"') continue
            cursor = skipWhitespace(html, cursor + 1)
            if (cursor >= html.length || html[cursor] != ':') continue
            return readEscapedString(html, skipWhitespace(html, cursor + 1))
        }
        return null
    }

    /** The string value (either `"..."` or `\"...\"`) starting at or after [from]; null if none. */
    private fun readEscapedString(html: String, from: Int): String? {
        var cursor = skipWhitespace(html, from)
        if (cursor + 1 < html.length && html[cursor] == '\\' && html[cursor + 1] == '"') cursor++
        if (cursor >= html.length || html[cursor] != '"') return null
        cursor++
        val out = StringBuilder()
        while (cursor < html.length) {
            val current = html[cursor]
            when {
                current == '"' -> return out.toString()
                current == '\\' && cursor + 1 < html.length && html[cursor + 1] == '"' -> return out.toString()
                current == '\\' && cursor + 1 < html.length -> {
                    out.append(html[cursor + 1])
                    cursor += 2
                }
                else -> {
                    out.append(current)
                    cursor++
                }
            }
        }
        return null
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
                return percentDecode(pair.substring(separator + 1)).takeIf { it.isNotBlank() }
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
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

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

    private val NOVEL_PATH = Regex("^/novela/([^/]+)/?$")
    private val CHAPTER_PATH = Regex("^/novela/([^/]+)/capitulo/([^/]+)/?$")
    private const val NEXT_IMAGE = "/_next/image"

    /** One `["<number>","<date>",...]` row of the `capitulos` array; group 1 is the chapter number. */
    private val CHAPTER_ROW = Regex("""\[\s*\\?"([^"\\]+)\\?"\s*,""")

    private const val CAPITULOS_KEY = "capitulos"
    private const val SLUG_KEY = "slug"

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
