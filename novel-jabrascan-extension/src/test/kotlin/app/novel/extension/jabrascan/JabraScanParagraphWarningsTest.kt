package app.novel.extension.jabrascan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The safety rules as a pure function: the paragraphs the extractor produced in, the warnings a reader
 * of the log should see out.
 *
 * The Android half — turning a warning into a `Log.w` line that names the work and the chapter — needs a
 * device and is covered by the instrumented test. What is pinned here is the decision itself: which
 * shapes are suspicious and which are not, with no device, no PDF and no `android.util.Log`.
 */
class JabraScanParagraphWarningsTest {

    @Test
    fun `a healthy chapter produces no warnings`() {
        val paragraphs = List(12) { "x".repeat(1200) }

        assertEquals(emptyList<String>(), JabraScanParagraphWarnings.warnings(paragraphs))
    }

    @Test
    fun `a paragraph just over the per-paragraph limit is warned`() {
        val paragraphs = listOf("x".repeat(JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS + 1))

        assertEquals(1, JabraScanParagraphWarnings.warnings(paragraphs).size)
    }

    @Test
    fun `a paragraph exactly at the per-paragraph limit is not warned`() {
        val paragraphs = listOf("x".repeat(JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS))

        assertEquals(emptyList<String>(), JabraScanParagraphWarnings.warnings(paragraphs))
    }

    @Test
    fun `a long chapter in one block is warned from both sides`() {
        val paragraphs = listOf("x".repeat(JabraScanParagraphWarnings.LONG_CHAPTER_CHARS + 1))

        val warnings = JabraScanParagraphWarnings.warnings(paragraphs)

        assertTrue(warnings.size >= 2, "expected a paragraph warning and a block warning, got: $warnings")
    }

    @Test
    fun `a long chapter split into a few short paragraphs is warned for its block count`() {
        val paragraphs = List(JabraScanParagraphWarnings.FEW_BLOCKS - 1) {
            "x".repeat(JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS / 3)
        }

        val warnings = JabraScanParagraphWarnings.warnings(paragraphs)

        assertEquals(
            1,
            warnings.size,
            "no paragraph is over the per-paragraph limit, so only the block count should warn: $warnings",
        )
    }

    @Test
    fun `a short chapter in one block is not warned`() {
        val paragraphs = listOf("x".repeat(1000))

        assertEquals(emptyList<String>(), JabraScanParagraphWarnings.warnings(paragraphs))
    }

    @Test
    fun `a long chapter split into many short-enough paragraphs is healthy`() {
        // Each paragraph sits exactly on the per-paragraph limit; what keeps the chapter quiet is the
        // block count, so this test ties the two thresholds together.
        val each = JabraScanParagraphWarnings.LONG_PARAGRAPH_CHARS
        val count = maxOf(
            JabraScanParagraphWarnings.FEW_BLOCKS,
            JabraScanParagraphWarnings.LONG_CHAPTER_CHARS / each + 1,
        )
        val paragraphs = List(count) { "x".repeat(each) }

        assertEquals(emptyList<String>(), JabraScanParagraphWarnings.warnings(paragraphs))
    }
}
