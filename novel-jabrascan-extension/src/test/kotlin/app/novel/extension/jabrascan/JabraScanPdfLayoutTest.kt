package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The paragraph rule as a pure function: a list of extracted lines in, the reader's paragraphs out.
 *
 * The lines here stand in for the shapes the spike measured on real jabrascan.net PDFs — word-by-word
 * baselines, running headers and footers, page numbers, a word split across lines, a paragraph running
 * off a page — without a device, a PDF or a dispatcher.
 */
class JabraScanPdfLayoutTest {

    @Test
    fun `a hard line break inside a paragraph becomes a single space`() {
        val lines = listOf(
            line("El faro parpadeaba", 100f),
            line("sobre el acantilado", 112f),
            line("cada madrugada", 124f),
        )

        assertEquals(
            listOf("El faro parpadeaba sobre el acantilado cada madrugada"),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `a word split across lines is sewn back without its hyphen`() {
        val lines = listOf(line("Fue algo extraordi-", 100f), line("nario de ver", 112f))

        assertEquals(listOf("Fue algo extraordinario de ver"), JabraScanPdfLayout.reconstruct(lines))
    }

    @Test
    fun `a soft hyphen left at a line break is removed too`() {
        val lines = listOf(line("extraordi\u00AD", 100f), line("nario", 112f))

        assertEquals(listOf("extraordinario"), JabraScanPdfLayout.reconstruct(lines))
    }

    @Test
    fun `a line-final hyphen before a capital stays a dash`() {
        val lines = listOf(line("Se fue-", 100f), line("Regresó", 112f))

        assertEquals(listOf("Se fue- Regresó"), JabraScanPdfLayout.reconstruct(lines))
    }

    @Test
    fun `running headers, footers and bare page numbers are dropped`() {
        val lines = listOf(
            line("La torre de cristal", 40f, page = 0),
            line("El pacto se selló", 100f, page = 0),
            line("12", 760f, page = 0),
            line("La torre de cristal", 40f, page = 1),
            line("entre dos reinos", 100f, page = 1),
            line("13", 760f, page = 1),
        )

        assertEquals(listOf("El pacto se selló entre dos reinos"), JabraScanPdfLayout.reconstruct(lines))
    }

    @Test
    fun `a paragraph break is the typical-leading rule and nothing else`() {
        val lines = listOf(
            line("Uno", 100f),
            line("dos", 112f),
            line("tres", 124f),
            line("Cuatro", 200f),
            line("cinco", 212f),
        )

        assertEquals(listOf("Uno dos tres", "Cuatro cinco"), JabraScanPdfLayout.reconstruct(lines))
    }

    @Test
    fun `a paragraph that runs off a page continues on the next one`() {
        val lines = listOf(
            line("La historia", 700f, page = 0),
            line("continúa", 712f, page = 0),
            line("al otro lado", 40f, page = 1),
            line("del papel", 52f, page = 1),
        )

        assertEquals(
            listOf("La historia continúa al otro lado del papel"),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `no lines read as no paragraphs`() {
        assertEquals(emptyList<String>(), JabraScanPdfLayout.reconstruct(emptyList()))
    }

    @Test
    fun `a single line is a single paragraph`() {
        val lines = listOf(line("Solo una línea", 100f))

        assertEquals(listOf("Solo una línea"), JabraScanPdfLayout.reconstruct(lines))
    }

    private fun line(text: String, baseline: Float, page: Int = 0) = ExtractedLine(text, baseline, page)
}
