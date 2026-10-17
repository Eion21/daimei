package app.novel.extension.jabrascan

/**
 * The one place a chapter's PDF address is assembled.
 *
 * The site keeps each chapter PDF under `books/{clave}/{NombreArchivo}` on one of two roots — the
 * GitHub Pages repository ([GITHUB_REPOSITORY]) for the works whose rows say `io-pdfs` or say nothing,
 * and the site itself ([SITE]) for the two that publish their PDFs locally — and joins the parts with a
 * plain `encodeURIComponent` on the file name. The encoding is the point: a `NombreArchivo` reaches this
 * layer exactly as the list writes it, and a real one does carry a space, a comma and accents
 * (`Señora, por favor compórtese-001.pdf`), so handing it to the HTTP layer raw would break the request.
 * Reproducing the join here, with the encoding, keeps a single definition of what the address looks like
 * instead of one per call site.
 *
 * The root is the caller's to choose (T6 picks it from the row's `server`), so this object answers one
 * question: given a root and a work, what is the encoded address of that file? The site's extra rule for
 * chapters past 999 — the leading digits fold into the folder name — will land here too, when T6 needs
 * it, so that "one place" stays true.
 */
internal object JabraScanUrls {

    /** The GitHub Pages repository the site serves most chapter PDFs from. */
    const val GITHUB_REPOSITORY: String = "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main"

    /** The site itself, which serves the works whose rows carry `"server": "local"`. */
    const val SITE: String = "https://jabrascan.net"

    /** `{root}/books/{clave}/{fileName}`, with [fileName] percent-encoded as a single path segment. */
    fun chapterPdfUrl(repositoryRoot: String, clave: String, fileName: String): String =
        repositoryRoot.trimEnd('/') + "/books/" + clave + "/" + encodePathSegment(fileName)

    /**
     * Percent-encodes [text] the way a URL path segment requires: every byte outside the RFC 3986
     * unreserved set (`A-Z a-z 0-9 - . _ ~`) becomes `%XX` in upper case. The text is processed as UTF-8
     * first, so `ó` becomes `%C3%B3` and `ñ` becomes `%C3%B1`, exactly as `encodeURIComponent` writes
     * them.
     */
    fun encodePathSegment(text: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(hex[value ushr 4]).append(hex[value and 0x0F])
            }
        }
        return out.toString()
    }
}
