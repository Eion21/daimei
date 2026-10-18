package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The emission half of the safety warnings, driven on a plain JVM.
 *
 * [JabraScanPdf] turns each warning [JabraScanParagraphWarnings] returns into a line that names the work
 * and the chapter. On the device that line goes through `android.util.Log.w`, which this test runner
 * cannot call: it runs with `isReturnDefaultValues = false`, so any real `Log` call dies with `Method w
 * in android.util.Log not mocked`. Its `emit` seam lets this test inject a sink instead and assert the
 * exact line a reader would see in logcat, with no device, no PDF and no `android.util.Log` on the path.
 *
 * The text is invented here; no third-party novel text is used.
 */
class JabraScanPdfWarningsEmitTest {

    @Test
    fun `a paragraph over the per-paragraph limit is emitted naming the work and the chapter`() {
        val chapter = ChapterIdentity(work = "The Invented Harbour", chapter = "Invented Chapter One")
        val paragraphs = listOf(inventedProse(JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS + 1))
        val captured = mutableListOf<String>()

        JabraScanPdf.warnOnSuspiciousLayout(chapter, paragraphs) { captured += it }

        assertEquals(1, captured.size, "expected only the per-paragraph warning, got: $captured")
        val line = captured.single()
        assertTrue(line.contains(chapter.work), "the work must be named: $line")
        assertTrue(line.contains(chapter.chapter), "the chapter must be named: $line")
        assertTrue(line.contains("paragraph rule"), "the per-paragraph rule must be named: $line")
    }

    @Test
    fun `a long chapter in too few blocks is emitted naming the work and the chapter`() {
        val chapter = ChapterIdentity(work = "The Invented Harbour", chapter = "Invented Chapter Two")
        val blocks = JabraScanParagraphWarnings.FEW_BLOCKS - 1
        val each = JabraScanParagraphWarnings.LONG_CHAPTER_CHARS / blocks + 1
        val paragraphs = List(blocks) { inventedProse(each) }
        val captured = mutableListOf<String>()

        JabraScanPdf.warnOnSuspiciousLayout(chapter, paragraphs) { captured += it }

        assertEquals(1, captured.size, "expected only the block-count warning, got: $captured")
        val line = captured.single()
        assertTrue(line.contains(chapter.work), "the work must be named: $line")
        assertTrue(line.contains(chapter.chapter), "the chapter must be named: $line")
        assertTrue(line.contains("failed to split"), "the block-count rule must be named: $line")
    }

    @Test
    fun `an unreliable leading that forced no separation is emitted naming the work and the chapter`() {
        val chapter = ChapterIdentity(work = "The Invented Harbour", chapter = "Invented Chapter Three")
        // Short enough to pass both paragraph warnings, so the only line is the unreliable-leading one.
        val paragraphs = listOf(inventedProse(1200))
        val captured = mutableListOf<String>()

        JabraScanPdf.warnOnSuspiciousLayout(
            chapter = chapter,
            paragraphs = paragraphs,
            leading = 0f,
            statisticUnreliable = true,
        ) { captured += it }

        assertEquals(1, captured.size, "expected only the unreliable-leading warning, got: $captured")
        val line = captured.single()
        assertTrue(line.contains(chapter.work), "the work must be named: $line")
        assertTrue(line.contains(chapter.chapter), "the chapter must be named: $line")
        assertTrue(line.contains("unreliable"), "the leading statistic must be named: $line")
        assertTrue(line.contains("not split"), "what was done about it must be named: $line")
    }

    @Test
    fun `an unreliable leading that fell back is emitted saying the leading was not trusted`() {
        val chapter = ChapterIdentity(work = "The Invented Harbour", chapter = "Invented Chapter Four")
        val paragraphs = listOf(inventedProse(1200))
        val captured = mutableListOf<String>()

        JabraScanPdf.warnOnSuspiciousLayout(
            chapter = chapter,
            paragraphs = paragraphs,
            leading = 10f,
            statisticUnreliable = true,
        ) { captured += it }

        assertEquals(1, captured.size, "expected only the unreliable-leading warning, got: $captured")
        val line = captured.single()
        assertTrue(line.contains(chapter.work), "the work must be named: $line")
        assertTrue(line.contains(chapter.chapter), "the chapter must be named: $line")
        assertTrue(line.contains("unreliable"), "the leading statistic must be named: $line")
        assertTrue(line.contains("median"), "the fallback must be named as what was done: $line")
    }

    @Test
    fun `a trusted leading emits no warning`() {
        val chapter = ChapterIdentity(work = "The Invented Harbour", chapter = "Invented Chapter Five")
        val paragraphs = List(12) { inventedProse(1200) }
        val captured = mutableListOf<String>()

        JabraScanPdf.warnOnSuspiciousLayout(
            chapter = chapter,
            paragraphs = paragraphs,
            leading = 11f,
            statisticUnreliable = false,
        ) { captured += it }

        assertEquals(emptyList<String>(), captured, "a trusted leading must stay silent")
    }

    /** Prose invented for this test, padded or trimmed to exactly [length] characters. */
    private fun inventedProse(length: Int): String {
        val sentence = "The harbour bell rang twice and the lanterns went dark. "
        return sentence.repeat(length / sentence.length + 1).take(length)
    }
}
