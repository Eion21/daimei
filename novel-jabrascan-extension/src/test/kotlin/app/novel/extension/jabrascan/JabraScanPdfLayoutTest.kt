package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The paragraph rule as a pure function: a list of extracted lines in, the reader's paragraphs out.
 *
 * The lines here stand in for the shapes measured on real jabrascan.net PDFs — word-by-word
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `a word split across lines is sewn back without its hyphen`() {
        val lines = listOf(line("Fue algo extraordi-", 100f), line("nario de ver", 112f))

        assertEquals(listOf("Fue algo extraordinario de ver"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
    }

    @Test
    fun `a soft hyphen left at a line break is removed too`() {
        val lines = listOf(line("extraordi\u00AD", 100f), line("nario", 112f))

        assertEquals(listOf("extraordinario"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
    }

    @Test
    fun `a line-final hyphen before a capital stays a dash`() {
        val lines = listOf(line("Se fue-", 100f), line("Regresó", 112f))

        assertEquals(listOf("Se fue- Regresó"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
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

        assertEquals(listOf("El pacto se selló entre dos reinos"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
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

        assertEquals(listOf("Uno dos tres", "Cuatro cinco"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `no lines read as no paragraphs`() {
        assertEquals(emptyList<String>(), JabraScanPdfLayout.reconstruct(emptyList()).paragraphs)
    }

    @Test
    fun `a single line is a single paragraph`() {
        val lines = listOf(line("Solo una línea", 100f))

        assertEquals(listOf("Solo una línea"), JabraScanPdfLayout.reconstruct(lines).paragraphs)
    }

    @Test
    fun `a chapter whose gaps are one single group is not split and flags the statistic`() {
        // Nineteen same-page gaps, all 12.0 pt: the p10 pool (everything at or below the p10) is the whole
        // population, so there is one single group and no ordinary-versus-break separation. The guard must
        // return a 0f leading — which is what keeps the chapter whole — and flag the statistic, so the
        // fallback leading the rule would otherwise have found (12.0) must NOT come back.
        val lines = (1..20).map { line("Line $it of the uniform block", (it - 1) * 12f) }

        val reconstruction = JabraScanPdfLayout.reconstruct(lines)

        assertEquals(0f, reconstruction.leading, "one group of gaps must not yield a usable leading")
        assertEquals(1, reconstruction.paragraphs.size, "one group of gaps must not split anything")
        assertTrue(
            reconstruction.statisticUnreliable,
            "one group of gaps must report the leading statistic as unreliable",
        )
    }

    @Test
    fun `one stray tiny gap does not drag the leading and flags the statistic`() {
        // Twenty lines, so nineteen same-page gaps: one stray 1.0 pt followed by eighteen ordinary 10.0 pt
        // gaps. With nineteen gaps the 10th-percentile index is 0, so the p10 lands on the stray and its
        // whole pool is that single isolated value — no 0.1 pt bucket holds two gaps. The stray must not set
        // the leading: the fallback keeps the ordinary 10.0 pt, so the eighteen ordinary gaps stay one
        // paragraph (a 1.0 pt leading would instead have broken all of them), and the statistic is flagged.
        val baselines = ArrayList<Float>(listOf(0f, 1f))
        repeat(18) { baselines.add(baselines.last() + 10f) }
        val lines = baselines.mapIndexed { i, baseline -> line("Line ${i + 1}", baseline) }

        val reconstruction = JabraScanPdfLayout.reconstruct(lines)

        assertEquals(10f, reconstruction.leading, "the stray 1.0 pt gap must not drag the leading down")
        assertEquals(1, reconstruction.paragraphs.size, "with a 10.0 pt leading the ordinary gaps stay whole")
        assertTrue(
            reconstruction.statisticUnreliable,
            "a p10 dragged onto an isolated gap must report the leading statistic as unreliable",
        )
    }

    private fun line(text: String, baseline: Float, page: Int = 0) = ExtractedLine(text, baseline, page)
}
