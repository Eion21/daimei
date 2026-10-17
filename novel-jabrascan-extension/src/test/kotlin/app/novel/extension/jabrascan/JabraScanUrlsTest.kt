package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The single PDF-address builder, on real shapes: an ordinary Arifureta file name, and a
 * MadamePleaseBehave file name, which carries a space, a comma and accents.
 */
class JabraScanUrlsTest {

    @Test
    fun `an ordinary real file name lands under the work's folder on the GitHub root`() {
        val url = JabraScanUrls.chapterPdfUrl(
            JabraScanUrls.GITHUB_REPOSITORY,
            "Arifureta",
            "Arifureta-0000.0v01_JabraScan.pdf",
        )

        assertEquals(
            "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main/" +
                "books/Arifureta/Arifureta-0000.0v01_JabraScan.pdf",
            url,
        )
    }

    @Test
    fun `a real name with a space, a comma and accents is percent-encoded as one segment`() {
        // MadamePleaseBehave publishes exactly this name (books/MadamePleaseBehave.json); the reader
        // must encode it rather than send the raw space, comma and accents to the HTTP layer.
        val url = JabraScanUrls.chapterPdfUrl(
            JabraScanUrls.GITHUB_REPOSITORY,
            "MadamePleaseBehave",
            "Señora, por favor compórtese-001.pdf",
        )

        assertEquals(
            "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main/" +
                "books/MadamePleaseBehave/Se%C3%B1ora%2C%20por%20favor%20comp%C3%B3rtese-001.pdf",
            url,
        )
    }

    @Test
    fun `a root with a trailing slash is not doubled`() {
        assertEquals(
            "https://jabrascan.net/books/MadamePleaseBehave/x.pdf",
            JabraScanUrls.chapterPdfUrl("https://jabrascan.net/", "MadamePleaseBehave", "x.pdf"),
        )
    }
}
