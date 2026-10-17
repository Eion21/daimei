package app.novel.extension.jabrascan

/**
 * An element scanner for `obras.xml`, the catalogue JabraScan writes by hand.
 *
 * The document is small, attribute-free and has no nesting to speak of — 49 `<obra>` elements, each a
 * flat list of elements — so a scanner over the raw text is enough, and it is all this extension may
 * use: no XML library is added to the APK.
 *
 * Two properties of that file are load-bearing here, both measured on the real one: elements the format
 * never defines (`pendejos`, `server`, `idioma`, `urlautor`, `alternate`) sit beside the ones it does,
 * and must be ignored rather than misread, and a `<sinopsis>` may hold its prose in a CDATA section or
 * as plain text, and the CDATA one may still carry HTML markup.
 *
 * The scanner refuses one thing: an element that is never closed. The only way that happens in practice
 * is a download that stopped in the middle, and a truncated catalogue silently read as a short one
 * would look to the reader like a library that lost its novels.
 */
internal object JabraScanXml {

    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"

    private val TAG = Regex("<[^>]*>")

    private val ENTITY = Regex("&(amp|lt|gt|quot|apos);")

    private val ENCODED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

    /**
     * The body of every `<[tag]>` element in [xml], in document order, without the tags themselves.
     *
     * [document] only names the file in a failure message, so a truncated catalogue says which of the
     * three documents stopped mid-way.
     */
    fun elements(xml: String, tag: String, document: String): List<String> {
        val open = "<$tag>"
        val close = "</$tag>"
        val bodies = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = xml.indexOf(open, cursor)
            if (start < 0) return bodies
            val end = xml.indexOf(close, start + open.length)
            if (end < 0) {
                error("$document: <$tag> opened at $start is never closed, so the document ends mid-element")
            }
            bodies += xml.substring(start + open.length, end)
            cursor = end + close.length
        }
    }

    /** The body of the first `<[tag]>` element, or null when the document has none. */
    fun element(xml: String, tag: String, document: String): String? =
        elements(xml, tag, document).firstOrNull()

    /**
     * The text of an element's [body]: CDATA unwrapped, markup removed, the five predefined XML
     * entities decoded. Whitespace is left as written; callers that need one line fold it themselves.
     *
     * Markup is removed inside the CDATA section too, because the real sinopsis that carries one carries
     * an `<a>` element as well. A lone `<` in the prose would be taken for the start of a tag and lost
     * up to the next `>`; the real `obras.xml` has none.
     */
    fun text(body: String): String = decode(unwrapCdata(body).replace(TAG, ""))

    /** Replaces `<![CDATA[` and `]]>` with nothing, so the section's own text joins the body's. */
    private fun unwrapCdata(body: String): String {
        if (!body.contains(CDATA_OPEN)) return body
        val out = StringBuilder(body.length)
        var cursor = 0
        while (true) {
            val start = body.indexOf(CDATA_OPEN, cursor)
            if (start < 0) {
                out.append(body, cursor, body.length)
                return out.toString()
            }
            val end = body.indexOf(CDATA_CLOSE, start + CDATA_OPEN.length)
            if (end < 0) {
                error("the document has a <![CDATA[ at $start that is never closed")
            }
            out.append(body, cursor, start)
            out.append(body, start + CDATA_OPEN.length, end)
            cursor = end + CDATA_CLOSE.length
        }
    }

    /** Decodes `&amp;` and the other four XML entities in a single pass, so `&amp;lt;` stays `&lt;`. */
    private fun decode(text: String): String =
        if (!text.contains('&')) {
            text
        } else {
            ENTITY.replace(text) { ENCODED[it.groupValues[1]] ?: it.value }
        }
}
