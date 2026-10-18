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
 * greater than 1.29x the leading, so with a 10.0 pt leading the threshold sits at 12.9 pt and the two
 * measured ends of the interval are 12.0 pt (1.20x, never a break) and 13.8 pt (1.38x, always one).
 */
class JabraScanPdfLeadingTest {

    @Test
    fun `a gap of 1 point 20 times the leading does not start a paragraph`() {
        // Gaps of 10.0 pt fix the leading at 10.0 (mode bucket 100) and the threshold at 12.9 pt.
        // The last gap is 12.0 pt: 1.20x the leading, the largest real intra-paragraph gap measured.
        val lines = listOf(
            line("The lighthouse keeper", 0f),
            line("climbed the iron stair", 10f),
            line("before the storm", 20f),
            line("and lit the lamp", 30f),
            line("just as the first wave", 42f),
        )

        assertEquals(
            listOf(
                "The lighthouse keeper climbed the iron stair before the storm and lit the lamp " +
                    "just as the first wave",
            ),
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `a gap of 1 point 38 times the leading does start a paragraph`() {
        // The same 10.0 pt leading, so the threshold is 12.9 pt; 13.8 pt is 1.38x, the tightest real break.
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `short dialogue lines at ordinary leading stay one paragraph`() {
        // Five lines, so four 10.0 pt gaps measure the leading; none exceeds the 12.9 pt threshold,
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `lines separated by a real paragraph gap each come out as their own paragraph`() {
        // Four 10.0 pt gaps fix the leading at 10.0 and the threshold at 12.9 pt; the 20.0 pt gaps that
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `a long paragraph of ordinary lines stays one paragraph`() {
        val lines = (1..25).map { line("Line $it of the long paragraph", (it - 1) * 10f) }

        assertEquals(
            listOf((1..25).joinToString(" ") { "Line $it of the long paragraph" }),
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
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
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
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

        val paragraphs = JabraScanPdfLayout.reconstruct(lines).paragraphs

        assertEquals(1, paragraphs.size, "fewer than four same-page gaps must not separate anything")
        val warnings = JabraScanParagraphWarnings.warnings(paragraphs)
        assertTrue(
            warnings.any { it.contains("failed to split") },
            "the block-count warning must fire for a long chapter in one block; got: $warnings",
        )
    }

    @Test
    fun `a tie between gap buckets keeps the smaller leading`() {
        // With twenty gaps the 10th-percentile index is 1, so the pool is everything at or below the
        // second-smallest gap: a single 10.0 pt and a single 12.0 pt tie at one member each. The smaller
        // bucket, 10.0, must win, so the threshold is 12.9 pt: the seventeen 12.5 pt ordinary gaps stay
        // inside it and only the final 14.0 pt gap breaks. Had the larger bucket won, the leading would be
        // 12.0 and its 15.48 pt threshold would keep that 14.0 pt gap in the paragraph.
        val baselines = ArrayList<Float>(listOf(0f, 10f, 22f))
        repeat(17) { baselines.add(baselines.last() + 12.5f) }
        baselines.add(baselines.last() + 14f)
        val lines = baselines.mapIndexed { i, baseline -> line("Line ${i + 1}", baseline) }

        val expectedFirst = (1..20).joinToString(" ") { "Line $it" }
        assertEquals(
            listOf(expectedFirst, "Line 21"),
            JabraScanPdfLayout.reconstruct(lines).paragraphs,
        )
    }

    @Test
    fun `many single-line paragraphs still find the ordinary leading`() {
        // A chapter of single-line paragraphs: the paragraph-break pitch dominates the gap population,
        // which is the shape that collapsed a real chapter under the old statistic. The ordinary leading
        // survives in the wrapped lines, and it is what the p10-anchored mode must return.
        val baselines = listOf(
            0f, 10f, 30f, 40f, 60f, 70f, 90f, 100f, 120f, 140f, 160f, 180f, 200f,
            220f, 240f, 260f, 280f, 300f, 320f, 340f, 360f,
        )
        val lines = baselines.mapIndexed { i, baseline -> line("Line ${i + 1}", baseline) }

        val paragraphs = JabraScanPdfLayout.reconstruct(lines).paragraphs

        assertEquals(17, paragraphs.size, "single-line paragraphs must not collapse the chapter into one")
    }

    private fun line(text: String, baseline: Float, page: Int = 0) = ExtractedLine(text, baseline, page)
}
