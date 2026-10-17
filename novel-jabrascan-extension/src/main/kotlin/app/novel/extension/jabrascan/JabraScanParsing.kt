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
 *   `<obra>` element parses to an empty page, a JSON document of the wrong shape reads as no data, and
 *   an entry with a damaged value is skipped — the rest of the document is still worth having. The one
 *   thing that *is* an error is a document that stops mid-way, because then the answer would be a short
 *   catalogue silently mistaken for the whole one.
 * - **Nothing here builds a URL.** `obras.xml` covers are paths relative to the library root and a
 *   chapter's address is the file name the list gives; which repository serves them is the source's
 *   concern (T6), and this layer records what the file says.
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

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The catalogue: one [Novel] per obra whose `visible` is not `no`, in the file's own order.
     *
     * The catalogue is the whole library — 48 of its 49 obras are visible — so it never pages.
     */
    fun parseCatalogue(xml: String): NovelPage =
        NovelPage(obras(xml).filter(Obra::visible).map(Obra::toNovel), hasNextPage = false)

    /**
     * The work's own entry, merged into what the host already holds.
     *
     * A work the catalogue does not carry is returned as it arrived rather than blanked, and `visible`
     * is deliberately not consulted: a novel the reader already has stays readable after the site drops
     * it from the front page.
     */
    fun parseNovelDetails(xml: String, novel: Novel): Novel {
        val obra = obras(xml).firstOrNull { it.clave == novel.id } ?: return novel
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
     * The chapter's id and url are both the file name the list gives; the host turns it into the PDF
     * address, and the format does not state it (T6).
     */
    fun parseChapterList(json: String, novel: Novel): List<Chapter> {
        val document = "books/${novel.id}.json"
        val root = JabraScanJson.asObject(JabraScanJson.parse(json, document)) ?: return emptyList()
        return chapters(root, novel.id).mapNotNull { chapter(it, novel) }
    }

    /**
     * Trims the text and folds every run of whitespace — the tabs and newlines a hand-written or
     * pretty-printed document carries — into a single space.
     */
    fun normalize(text: String): String = WHITESPACE.replace(text, " ").trim()

    // ---- The catalogue -------------------------------------------------------------------------

    private fun obras(xml: String): List<Obra> =
        JabraScanXml.elements(xml, OBRA, CATALOGUE_DOCUMENT).mapNotNull(::obra)

    /**
     * One `<obra>` element: the fields the reader is shown, with the elements the format does not
     * define ignored.
     *
     * An obra with no `clave` is nothing to address it by and one with no `nombreobra` is nothing to
     * render, so both are left out. `visible` is kept as written, and only a literal `no` hides the
     * obra: a missing value is a document that does not say, not a site that said no.
     */
    private fun obra(body: String): Obra? {
        val clave = text(body, CLAVE) ?: return null
        val title = JabraScanXml.elements(body, TITLE, CATALOGUE_DOCUMENT)
            .map(JabraScanXml::text)
            .map(::normalize)
            .firstOrNull(String::isNotEmpty) ?: return null
        return Obra(
            clave = clave,
            visible = text(body, VISIBLE)?.equals(HIDDEN, ignoreCase = true) != true,
            title = title,
            author = text(body, AUTHOR),
            // The sinopsis is the prose; `meta` is the one-line fallback the same file carries.
            description = text(body, SYNOPSIS) ?: text(body, SUMMARY),
            coverUrl = text(body, COVER),
        )
    }

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

    /**
     * The chapter array: the one under the work's own clave, or, when the document holds a single array,
     * that one — the same list under a key that does not match the clave is still this work's list.
     */
    private fun chapters(root: Map<String, Any?>, clave: String): List<Any?> =
        JabraScanJson.asArray(root[clave])
            ?: JabraScanJson.asArray(root.values.singleOrNull())
            ?: emptyList()

    /**
     * One chapter row. A row with no file name has no address and is dropped; a row with no number or
     * no title is still readable, so it is kept with what the file does say.
     */
    private fun chapter(value: Any?, novel: Novel): Chapter? {
        val row = JabraScanJson.asObject(value) ?: return null
        val file = row.text(FILE) ?: return null
        val number = JabraScanJson.asFloat(row[NUMBER])
        val title = row.text(CHAPTER_TITLE) ?: row.text(NUMBER)?.let { "Capítulo $it" } ?: file
        return Chapter(SOURCE_ID, novel.id, file, title, number, file)
    }

    private fun Map<String, Any?>.text(key: String): String? =
        JabraScanJson.asString(this[key])?.trim()?.takeIf(String::isNotEmpty)
}
