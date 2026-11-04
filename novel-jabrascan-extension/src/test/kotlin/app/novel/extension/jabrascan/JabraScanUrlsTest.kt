package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The single PDF-address builder, on real shapes: an ordinary Arifureta file name, a
 * MadamePleaseBehave file name, which carries a space, a comma and accents, and the fold past
 * chapter 999 — which is the site's rule and applies to every root.
 *
 * One measured work breaks the fold: `MyCollegeTeacherMyChildrenMom` keeps its repository folder
 * unfolded for its 33 rows past 999, so the folded address 404s (the work 404s on the site too).
 * The rule is not special-cased for it; the tests below pin both the rule and that exception.
 */
class JabraScanUrlsTest {

    @Test
    fun `pdf host omits books prefix and folds and encodes the file`() {
        assertEquals(
            "https://books.jabrascan.net/Torre1/Se%C3%B1ora%2C%20por%20favor.pdf",
            JabraScanUrls.chapterPdfUrl(
                JabraScanUrls.BOOKS_ROOT + "/",
                "Torre",
                "Señora, por favor.pdf",
                1031f,
            ),
        )
    }

    @Test
    fun `an ordinary real file name lands under the work's folder on the GitHub root`() {
        val url = JabraScanUrls.chapterPdfUrl(
            JabraScanUrls.PDF_REPOSITORY,
            "Arifureta",
            "Arifureta-0000.0v01_JabraScan.pdf",
            0f,
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
            JabraScanUrls.PDF_REPOSITORY,
            "MadamePleaseBehave",
            "Señora, por favor compórtese-001.pdf",
            1f,
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
            JabraScanUrls.chapterPdfUrl("https://jabrascan.net/", "MadamePleaseBehave", "x.pdf", 1f),
        )
    }

    @Test
    fun `past chapter 999 the folder folds on every root, repository and site alike`() {
        // Measured: ShadowSlave 1031 and CultivationOnline 1000 serve from the folded folder on their
        // roots (folded 200 / unfolded 404), so the fold belongs to the address, not to one root.
        assertEquals(
            "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main/" +
                "books/ShadowSlave1/ShadowSlave-1031.pdf",
            JabraScanUrls.chapterPdfUrl(
                JabraScanUrls.PDF_REPOSITORY,
                "ShadowSlave",
                "ShadowSlave-1031.pdf",
                1031f,
            ),
        )
        assertEquals(
            "https://jabrascan.net/books/CultivationOnline1/CultivationOnline-1000.pdf",
            JabraScanUrls.chapterPdfUrl(
                JabraScanUrls.SITE,
                "CultivationOnline",
                "CultivationOnline-1000.pdf",
                1000f,
            ),
        )
    }

    @Test
    fun `the known fold exception is MyCollegeTeacherMyChildrenMom, whose repository folder is flat`() {
        // This work's 33 rows past 999 live under the bare clave, so the folded address this builder
        // produces 404s. The host turns that into `HTTP 404 fetching <url>` (HostNovelNetwork), so the
        // attempted address is named in the failure. No fallback and no per-work table: the fold is
        // what the site's own reader uses and what the other measured works serve.
        assertEquals(
            "https://raw.githubusercontent.com/JabraScan/JabraScan.github.io-pdfs/main/" +
                "books/MyCollegeTeacherMyChildrenMom1/MyCollegeTeacherMyChildrenMom-1030.pdf",
            JabraScanUrls.chapterPdfUrl(
                JabraScanUrls.PDF_REPOSITORY,
                "MyCollegeTeacherMyChildrenMom",
                "MyCollegeTeacherMyChildrenMom-1030.pdf",
                1030f,
            ),
        )
    }
}
