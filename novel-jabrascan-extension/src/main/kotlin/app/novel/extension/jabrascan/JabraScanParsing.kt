package app.novel.extension.jabrascan

import app.novel.source.api.Chapter
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage

/**
 * The three JabraScan documents as data: the catalogue (`obras.xml`), the chapter index
 * (`capitulos.json`) and a work's chapter list (`books/{clave}.json`).
 *
 * Everything here is a pure function over a `String` the source has already fetched, so it can be
 * tested without a network, a device or a file, and it belongs to no dispatcher. Two of its decisions
 * are worth stating:
 *
 * - **A document that is empty, foreign or merely odd is not an error.** The catalogue that carries no
 *   `<obra>` element parses to an empty page, a JSON document of the wrong shape reads as no data, and a
 *   row with a damaged value is skipped — the rest of the document is still worth having. Three shapes
 *   *are* errors, each because the silent answer would be a lie: a document that stops mid-way (a short
 *   catalogue taken for the whole one), a catalogue whose every obra was discarded (a library that looks
 *   as if it lost its novels), and a chapter list whose chapters sit under a key that is not the work's
 *   (they would be filed, with their progress and downloads, under the wrong novel).
 * - **URL assembly belongs to [JabraScanUrls].** Catalogue cover paths are converted there for both
 *   catalogue and details; chapter PDF addresses are assembled only when the bytes are requested (T6).
 */
internal object JabraScanParsing {

    const val SOURCE_ID: String = "jabrascan"

    /** Named apart so a failure message says which document stopped mid-way. */
    private const val CATALOGUE_DOCUMENT = "obras.xml"
    private const val INDEX_DOCUMENT = "capitulos.json"

    private const val OBRA = "obra"
    private const val CLAVE = "clave"
    private const val VISIBLE = "visible"
    private const val HIDDEN = "no"
    private const val TITLE = "nombreobra"
    private const val AUTHOR = "autor"
    private const val SYNOPSIS = "sinopsis"
    private const val SUMMARY = "meta"
    private const val COVER = "imagen"

    private const val FILE = "NombreArchivo"
    private const val NUMBER = "numCapitulo"
    private const val CHAPTER_TITLE = "nombreCapitulo"
    private const val SERVER = "server"

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The catalogue: one [Novel] per obra whose `visible` is not `no`, in the file's own order.
     *
     * The catalogue is the whole library — 48 of its 49 obras are visible — so it never pages. This is
     * [parseCatalogueReport]'s page without its discards, for callers that do not need them.
     */
    fun parseCatalogue(xml: String): NovelPage = parseCatalogueReport(xml).page

    /**
     * The catalogue and the obras it could not use, each with the reason it was dropped.
     *
     * An obra with no `clave` has nothing to address it by and one with no non-empty `nombreobra` has
     * nothing to render, so both are left out — but the reader is entitled to know the catalogue shrank,
     * so the discards are returned rather than swallowed. This layer stays pure, so the caller (the
     * downloading layer) is the one that logs them.
     *
     * A catalogue whose every obra was discarded is not an empty catalogue but a broken one, and it
     * fails loudly: the reader would otherwise see a library that lost its novels. A document with no
     * `<obra>` at all is still a normal empty page, because then nothing was lost. Duplicate claves are
     * refused, including on hidden or discarded obras.
     */
    fun parseCatalogueReport(xml: String): CatalogueResult {
        val scan = readCatalogue(xml)
        if (scan.obras.isEmpty() && scan.discarded.isNotEmpty()) {
            error(
                "$CATALOGUE_DOCUMENT: all ${scan.discarded.size} <obra> were discarded " +
                    "(${discardSummary(scan.discarded)}), so this is a broken catalogue, not an empty one",
            )
        }
        return CatalogueResult(
            page = NovelPage(scan.obras.filter(Obra::visible).map(Obra::toNovel), hasNextPage = false),
            discarded = scan.discarded,
        )
    }

    /**
     * The work's own entry, merged into what the host already holds.
     *
     * A work the catalogue does not carry is returned as it arrived rather than blanked, and `visible`
     * is deliberately not consulted: a novel the reader already has stays readable after the site drops
     * it from the front page. Neither is the all-discarded failure raised here: this reads one obra by
     * `clave` and has nothing to say about the catalogue as a whole.
     */
    fun parseNovelDetails(xml: String, novel: Novel): Novel {
        val obra = readCatalogue(xml).obras.firstOrNull { it.clave == novel.id } ?: return novel
        return novel.copy(
            title = obra.title,
            author = obra.author ?: novel.author,
            description = obra.description ?: novel.description,
            coverUrl = obra.coverUrl ?: novel.coverUrl,
        )
    }

    /**
     * The local search, because the site has none: `obras.xml` is fetched whole, so the query is
     * matched against the titles here. An empty query is not a search and asks for everything back.
     */
    fun filterByTitle(novels: List<Novel>, query: String): List<Novel> {
        val needle = foldForSearch(query)
        if (needle.isBlank()) return novels
        return novels.filter { foldForSearch(it.title).contains(needle) }
    }

    /** Case- and accent-insensitive (`Obra mínima` folds to `obra minima`), because readers type both. */
    fun foldForSearch(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()

    /**
     * `capitulos.json`: each clave to the path of its chapter list, exactly as the file writes it
     * (`books/TorreDeCristal.json`). An entry whose value is not a usable path is skipped, because one
     * damaged key should not cost the reader the rest of the index.
     */
    fun parseChapterIndex(json: String): Map<String, String> {
        val root = JabraScanJson.asObject(JabraScanJson.parse(json, INDEX_DOCUMENT)) ?: return emptyMap()
        val paths = LinkedHashMap<String, String>(root.size)
        for ((clave, value) in root) {
            val path = JabraScanJson.asString(value)?.trim()?.takeIf(String::isNotEmpty) ?: continue
            paths[clave] = path
        }
        return paths
    }

    /**
     * `books/{clave}.json`: the work's chapters in the order the file lists them, which is the order
     * they were published in.
     *
     * Only the array under the work's own `clave` is read; a document that carries an array under some
     * other key is refused rather than borrowed, because saving another work's chapters — with their
     * progress and downloads — under this novel is worse than an error. The chapter's id is the stable,
     * unencoded pair of the work and the file name the row gives; the PDF address is built later, in
     * [JabraScanUrls].
     *
     * This is [parseChapterListReport]'s list alone, for callers that never need a row's `server`.
     */
    fun parseChapterList(json: String, novel: Novel): List<Chapter> = parseChapterListReport(json, novel).chapters

    /**
     * The work's chapters and, per file name, the `server` its row names — `null` when the row carries
     * none.
     *
     * The list and the servers travel together because the root a chapter is downloaded from is a
     * property of its own row, not of the work: `books/CDMNQTMHC.json` and `CultivationOnline` mix rows
     * for the repository with rows for the site, so the source keeps the map while it reads the list and
     * consults it when the bytes are requested. Reading it here, pure and without a network, keeps that
     * decision off the request path and testable. A row's `server` is kept exactly as written (only
     * trimmed): which values are usable is the source's decision, not this layer's, and an unknown one
     * must survive so the source can keep the row instead of silently dropping it. Duplicate file names
     * are refused; numbers that cannot be represented as finite floats leave the chapter's number null.
     */
    fun parseChapterListReport(json: String, novel: Novel): ChapterListResult {
        val document = "books/${novel.id}.json"
        val root = JabraScanJson.asObject(JabraScanJson.parse(json, document))
            ?: return ChapterListResult(emptyList(), emptyMap())
        val chapters = ArrayList<Chapter>()
        val servers = LinkedHashMap<String, String?>()
        for (value in chapters(root, novel.id, document)) {
            val row = chapterRow(value, novel) ?: continue
            if (servers.containsKey(row.file)) error("$document: duplicate chapter file '${row.file}'")
            chapters += row.chapter
            servers[row.file] = row.server
        }
        return ChapterListResult(chapters, servers)
    }

    /**
     * Trims the text and folds every run of whitespace — the tabs and newlines a hand-written or
     * pretty-printed document carries — into a single space.
     */
    fun normalize(text: String): String = WHITESPACE.replace(text, " ").trim()

    // ---- The catalogue -------------------------------------------------------------------------

    /**
     * One pass over the catalogue's `<obra>` elements: the obras the reader can use, and the ones it
     * cannot, each with the reason it was left out.
     *
     * An obra's fields are the ones the reader is shown, with the elements the format does not define
     * ignored. `visible` is kept as written, and only a literal `no` hides the obra: a missing value is
     * a document that does not say, not a site that said no. Hiding is not discarding — a hidden obra
     * still parses and is filtered later — so it never appears among the discards.
     */
    private fun readCatalogue(xml: String): CatalogueScan {
        val obras = mutableListOf<Obra>()
        val discarded = mutableListOf<DiscardedObra>()
        val claves = mutableSetOf<String>()
        for (body in JabraScanXml.elements(xml, OBRA, CATALOGUE_DOCUMENT)) {
            val clave = text(body, CLAVE)
            if (clave == null) {
                discarded += DiscardedObra(clave = null, reason = DiscardReason.MISSING_CLAVE)
                continue
            }
            if (!claves.add(clave)) error("$CATALOGUE_DOCUMENT: duplicate clave '$clave'")
            val title = JabraScanXml.elements(body, TITLE, CATALOGUE_DOCUMENT)
                .map(JabraScanXml::text)
                .map(::normalize)
                .firstOrNull(String::isNotEmpty)
            if (title == null) {
                discarded += DiscardedObra(clave = clave, reason = DiscardReason.MISSING_TITLE)
                continue
            }
            obras += Obra(
                clave = clave,
                visible = text(body, VISIBLE)?.equals(HIDDEN, ignoreCase = true) != true,
                title = title,
                author = text(body, AUTHOR),
                // The sinopsis is the prose; `meta` is the one-line fallback the same file carries.
                description = text(body, SYNOPSIS) ?: text(body, SUMMARY),
                coverUrl = JabraScanUrls.coverUrl(text(body, COVER)),
            )
        }
        return CatalogueScan(obras, discarded)
    }

    private data class CatalogueScan(val obras: List<Obra>, val discarded: List<DiscardedObra>)

    /** A compact log-ready count of the reasons, for the all-discarded failure message. */
    private fun discardSummary(discarded: List<DiscardedObra>): String =
        discarded.groupingBy(DiscardedObra::reason).eachCount()
            .entries.joinToString(", ") { "${it.value} × ${it.key}" }

    /** The catalogue's page and the obras it dropped. */
    internal data class CatalogueResult(val page: NovelPage, val discarded: List<DiscardedObra>)

    /** One `<obra>` the catalogue could not use: its `clave` when it has one, and why it was dropped. */
    internal data class DiscardedObra(val clave: String?, val reason: DiscardReason) {
        /** The reason as one line, for the downloading layer to log. */
        fun describe(): String = when (reason) {
            DiscardReason.MISSING_CLAVE -> "obra dropped: no <clave>, so it cannot be addressed"
            DiscardReason.MISSING_TITLE ->
                "obra \"$clave\" dropped: no non-empty <nombreobra>, so there is nothing to render"
        }
    }

    /** Why an obra was left out of the catalogue. */
    internal enum class DiscardReason { MISSING_CLAVE, MISSING_TITLE }

    /** The folded text of the element's first body, or null when the element is absent or empty. */
    private fun text(body: String, tag: String): String? =
        JabraScanXml.element(body, tag, CATALOGUE_DOCUMENT)
            ?.let(JabraScanXml::text)
            ?.let(::normalize)
            ?.takeIf(String::isNotEmpty)

    private data class Obra(
        val clave: String,
        val visible: Boolean,
        val title: String,
        val author: String?,
        val description: String?,
        val coverUrl: String?,
    ) {
        fun toNovel(): Novel = Novel(SOURCE_ID, clave, title, author, description, coverUrl)
    }

    // ---- The chapter list ----------------------------------------------------------------------

    /** The work's chapters and the `server` each file's row names, or `null` when it names none. */
    internal data class ChapterListResult(val chapters: List<Chapter>, val servers: Map<String, String?>)

    /**
     * The chapter array: the one under the work's own `clave`, and nothing else.
     *
     * A clave that is present but does not hold an array is a damaged document rather than a mix-up, so
     * it reads as no chapters. A clave that is absent while another key holds an array is the dangerous
     * case — reading it would file a different work's chapters under this one — and it fails, naming
     * both the work and the key that was found.
     */
    private fun chapters(root: Map<String, Any?>, clave: String, document: String): List<Any?> {
        if (root.containsKey(clave)) return JabraScanJson.asArray(root[clave]) ?: emptyList()
        val elsewhere = root.keys.filter { JabraScanJson.asArray(root[it]) != null }
        if (elsewhere.isEmpty()) return emptyList()
        error(
            "$document: no chapters under \"$clave\", but ${elsewhere.joinToString { "\"$it\"" }} holds an " +
                "array; it is not read as this work's list",
        )
    }

    /**
     * One chapter row, with the file name kept twice: inside the [Chapter] identity and as the key of
     * the server map. A row with no file name has no address and is dropped; a row with no number or
     * no title is still readable, so it is kept with what the file does say.
     *
     * The id is the stable key the reader stores — the work and the file name, unencoded — so it does
     * not move when the address scheme does. The url stays the bare file name the list gives; the full
     * address is assembled, encoded, only in [JabraScanUrls] when the PDF is requested.
     */
    private fun chapterRow(value: Any?, novel: Novel): ParsedChapter? {
        val row = JabraScanJson.asObject(value) ?: return null
        val file = row.text(FILE) ?: return null
        val number = JabraScanJson.asFloat(row[NUMBER])
        val title = row.text(CHAPTER_TITLE) ?: row.text(NUMBER)?.let { "Capítulo $it" } ?: file
        return ParsedChapter(
            chapter = Chapter(SOURCE_ID, novel.id, "${novel.id}/$file", title, number, file),
            file = file,
            server = row.text(SERVER),
        )
    }

    private data class ParsedChapter(val chapter: Chapter, val file: String, val server: String?)

    private fun Map<String, Any?>.text(key: String): String? =
        JabraScanJson.asString(this[key])?.trim()?.takeIf(String::isNotEmpty)
}
