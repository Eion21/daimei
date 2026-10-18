package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The leading statistic and the paragraph threshold, pinned against exact numbers.
 *
 * [JabraScanPdfLayoutTest] drives the rule end to end on the shapes the spike measured; this class
 * isolates the two decisions this change made: how the page's typical leading is derived, and at how many
 * leads of gap a line starts a paragraph. Every case is stated as geometry — numeric baselines on one
 * page — and asserted through the paragraphs a reader would see, never through internals.
 *
 * The prose is invented here; no third-party novel text is used. A paragraph break is a gap strictly
 * greater than 1.32x the leading, so with a 10.0 pt leading the threshold sits at 13.2 pt and the two
 * measured extremes are 12.6 pt (1.26x, never a break) and 13.8 pt (1.38x, always one).
 */
class JabraScanPdfLeadingTest {

    @Test
    fun `a gap of 1 point 26 times the leading does not start a paragraph`() {
        // Gaps of 10.0 pt fix the leading at 10.0 (mode bucket 100) and the threshold at 13.2 pt.
        // The last gap is 12.6 pt: 1.26x the leading, the largest real intra-paragraph gap measured.
        val lines = listOf(
            line("The lighthouse keeper", 0f),
            line("climbed the iron stair", 10f),
            line("before the storm", 20f),
            line("and lit the lamp", 30f),
            line("just as the first wave", 42.6f),
        )

        assertEquals(
            listOf(
                "The lighthouse keeper climbed the iron stair before the storm and lit the lamp " +
                    "just as the first wave",
            ),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `a gap of 1 point 38 times the leading does start a paragraph`() {
        // The same 10.0 pt leading, so the threshold is 13.2 pt; 13.8 pt is 1.38x, the tightest real break.
        val lines = listOf(
            line("The lighthouse keeper", 0f),
            line("climbed the iron stair", 10f),
            line("before the storm", 20f),
            line("and lit the lamp", 30f),
            line("Then the wind fell quiet", 43.8f),
        )

        assertEquals(
            listOf(
                "The lighthouse keeper climbed the iron stair before the storm and lit the lamp",
                "Then the wind fell quiet",
            ),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `short dialogue lines at ordinary leading stay one paragraph`() {
        // Five lines, so four 10.0 pt gaps measure the leading; none exceeds the 13.2 pt threshold,
        // including the line that ends in a dialogue dash.
        val lines = listOf(
            line("—Are you leaving?", 0f),
            line("—I have to, she said.", 10f),
            line("—Then go,\u201d he whispered.", 20f),
            line("—But I will wait—", 30f),
            line("and she closed the door.", 40f),
        )

        assertEquals(
            listOf(
                "—Are you leaving? —I have to, she said. —Then go,\u201d he whispered. " +
                    "—But I will wait— and she closed the door.",
            ),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `lines separated by a real paragraph gap each come out as their own paragraph`() {
        // Four 10.0 pt gaps fix the leading at 10.0 and the threshold at 13.2 pt; the 20.0 pt gaps that
        // follow are real breaks, so each trailing line is a paragraph of its own and none merges.
        val lines = listOf(
            line("The first line of body text", 0f),
            line("continues on the second", 10f),
            line("and the third", 20f),
            line("and the fourth", 30f),
            line("and closes here", 40f),
            line("A lone closing line", 60f),
            line("Another lone line", 80f),
            line("The last lone line", 100f),
        )

        assertEquals(
            listOf(
                "The first line of body text continues on the second and the third and the fourth " +
                    "and closes here",
                "A lone closing line",
                "Another lone line",
                "The last lone line",
            ),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `a long paragraph of ordinary lines stays one paragraph`() {
        val lines = (1..25).map { line("Line $it of the long paragraph", (it - 1) * 10f) }

        assertEquals(
            listOf((1..25).joinToString(" ") { "Line $it of the long paragraph" }),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `a document with fewer than four same-page gaps becomes one paragraph`() {
        // Four lines share the page, so only three gaps exist: below the four the leading needs, it is
        // unmeasurable (0f) and nothing is separated, however large the jumps look.
        val lines = listOf(
            line("Only four lines here", 0f),
            line("with a huge jump", 100f),
            line("that would normally split", 110f),
            line("but cannot be measured", 300f),
        )

        assertEquals(
            listOf("Only four lines here with a huge jump that would normally split but cannot be measured"),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    @Test
    fun `an unmeasurable leading collapses a long chapter into one block that warns`() {
        // The same unmeasurable shape, but long enough that the collapse must be reported: one block over
        // the per-paragraph limit, and a chapter over the floor in fewer than the few-blocks minimum.
        val prose = "The harbour bell rang twice and the lanterns went dark. ".repeat(40)
        val lines = listOf(
            line(prose, 0f),
            line(prose, 100f),
            line(prose, 200f),
            line(prose, 300f),
        )

        val paragraphs = JabraScanPdfLayout.reconstruct(lines)

        assertEquals(1, paragraphs.size, "fewer than four same-page gaps must not separate anything")
        val warnings = JabraScanParagraphWarnings.warnings(paragraphs)
        assertTrue(
            warnings.any { it.contains("failed to split") },
            "the block-count warning must fire for a long chapter in one block; got: $warnings",
        )
    }

    @Test
    fun `a tie between gap buckets keeps the smaller leading`() {
        // The gaps at or below the 12.0 median are 10, 10, 12, 12: buckets 100 and 120 tie at two members
        // each. The smaller bucket, 10.0, must win, so the threshold is 13.2 pt and the final 14.0 pt gap
        // breaks. Had the larger bucket won, the leading would be 12.0 and its 15.84 pt threshold would
        // keep that 14.0 pt gap inside the paragraph.
        val lines = listOf(
            line("One", 0f),
            line("two", 10f),
            line("three", 20f),
            line("four", 32f),
            line("five", 44f),
            line("Six", 58f),
        )

        assertEquals(
            listOf("One two three four five", "Six"),
            JabraScanPdfLayout.reconstruct(lines),
        )
    }

    private fun line(text: String, baseline: Float, page: Int = 0) = ExtractedLine(text, baseline, page)
}
