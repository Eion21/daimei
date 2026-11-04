package app.novel.extension.jabrascan

/**
 * The roots every JabraScan address hangs off, and the one place a chapter's PDF address is assembled.
 *
 * The library is spread over three roots, and each one came from a measured response rather than a guess:
 *
 * - **the content repository** ([CONTENT_REPOSITORY]) is JabraScan's own GitHub Pages repository
 *   (`JabraScan/JabraScan.github.io`), served through `raw.githubusercontent.com`. Its `main` branch carries
 *   the catalogue `obras.xml`, the chapter index `capitulos.json`, and chapter lists `books/{clave}.json`;
 * - **the PDF host** ([BOOKS_ROOT], `https://books.jabrascan.net`) serves PDFs whose `server` is absent
 *   or says `io-pdfs`, directly under the work folder without a `/books` prefix;
 * - **the site itself** ([SITE], `https://jabrascan.net`) serves the chapter PDFs of the rows whose `server`
 *   says `local`.
 *
 * The root is the caller's to choose (the source picks it from the row's `server`), so this object answers
 * one question: given a root, a work and a row, what is the encoded address of that row's file?
 *
 * Two rules make the address more than a join. The folder is not always the bare clave: past chapter 999
 * the site folds the leading digits of the number into it (`1234` puts the file under `...1`), so the number
 * is part of the address and is assembled here rather than at the call site. And the file name is one path
 * segment that reaches this layer raw — a real one carries a space, a comma and accents
 * (`Señora, por favor compórtese-001.pdf`) — so it is percent-encoded here instead of being handed to the
 * HTTP layer, which would either send the raw space or encode the way `URLEncoder` does (`+` for a space,
 * wrong for a path).
 */
internal object JabraScanUrls {

    /** The GitHub repository holding the catalogue, index, and chapter lists. */
    const val CONTENT_REPOSITORY: String = "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io/main"

    /** Legacy root retained for callers that build the historical `/books` address. */
    const val PDF_REPOSITORY: String = "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main"

    /** The PDF host used for absent and `io-pdfs` server values. */
    const val BOOKS_ROOT: String = "https://books.jabrascan.net"

    /** The site itself, which serves the works whose rows carry `"server": "local"`. */
    const val SITE: String = "https://jabrascan.net"

    /** The site's responsive cover URL for a catalogue image path. */
    fun coverUrl(image: String?): String? {
        val path = image?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        val stem = path.replace(Regex("\\.(jpg|jpeg|png|webp|avif)$", RegexOption.IGNORE_CASE), "")
        return "https://img.jabrascan.net/" + stem.split('/').joinToString("/", transform = ::encodePathSegment) +
            "-225w.webp"
    }

    /** The last chapter number the site keeps under the bare clave folder. */
    private const val LAST_UNFOLDED_CHAPTER = 999f

    /** How many trailing digits of a folded chapter number are dropped from the folder. */
    private const val FOLDED_DIGITS = 3

    /**
     * `{root}/{folder}/{fileName}` for the PDF host, or `{root}/books/{folder}/{fileName}` for other
     * roots. The file is encoded as one segment and the folder is folded from [number].
     */
    fun chapterPdfUrl(repositoryRoot: String, clave: String, fileName: String, number: Float?): String {
        val root = repositoryRoot.trimEnd('/')
        val prefix = if (root == BOOKS_ROOT) "/" else "/books/"
        return root + prefix + encodePathSegment(foldedClave(clave, number)) + "/" + encodePathSegment(fileName)
    }

    /**
     * The site's folder for a work: the clave, plus — for a chapter numbered past 999 — the leading digits
     * of the number with the last three removed. The fold is the site's, not a local convention: it keeps
     * one directory from collecting thousands of files, so the folder is part of the measured address.
     */
    private fun foldedClave(clave: String, number: Float?): String {
        if (number == null || number <= LAST_UNFOLDED_CHAPTER) return clave
        return clave + number.toInt().toString().dropLast(FOLDED_DIGITS)
    }

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
