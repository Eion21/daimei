package app.novel.reader.compose

import app.novel.reader.core.ReadingPosition
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderInteractionTest {
    @Test
    fun `only taps in the upper half toggle controls`() {
        assertTrue(isReaderControlsTap(0f, 800))
        assertTrue(isReaderControlsTap(399f, 800))
        assertFalse(isReaderControlsTap(400f, 800))
        assertFalse(isReaderControlsTap(799f, 800))
    }

    @Test
    fun `invalid viewport coordinates cannot toggle controls`() {
        assertFalse(isReaderControlsTap(-1f, 800))
        assertFalse(isReaderControlsTap(0f, 0))
        assertFalse(isReaderControlsTap(Float.NaN, 800))
    }

    @Test
    fun `footer geometry never becomes a saved content index or fraction`() {
        val contentAndFooter = sampleReaderProgress(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 100,
            visible = listOf(ReaderVisibleItem(2, -100, 400), ReaderVisibleItem(3, 300, 400)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )
        val footerOnly = sampleReaderProgress(
            firstVisibleItemIndex = 3,
            firstVisibleItemScrollOffset = 100,
            visible = listOf(ReaderVisibleItem(3, -100, 600)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )

        assertAll(
            { assertEquals(ReadingPosition(2, 0.25f), contentAndFooter.position) },
            { assertEquals(ReadingPosition(2, 1f), footerOnly.position) },
            { assertTrue(footerOnly.atEnd, "Only the footer remains after the real content has ended") },
        )
    }

    @Test
    fun `last real content still completes when the footer is the last visible item`() {
        val sample = sampleReaderProgress(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 8,
            visible = listOf(ReaderVisibleItem(2, -8, 400), ReaderVisibleItem(3, 392, 100)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )

        assertAll(
            { assertEquals(ReadingPosition(2, 0.02f), sample.position) },
            { assertTrue(sample.atEnd, "The footer must not hide completion of the last content block") },
        )
    }

    @Test
    fun `without a footer ordinary content progress and reached bottom remain unchanged`() {
        val middle = sampleReaderProgress(
            firstVisibleItemIndex = 1,
            firstVisibleItemScrollOffset = 100,
            visible = listOf(ReaderVisibleItem(1, -100, 600)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )
        val bottom = sampleReaderProgress(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 100,
            visible = listOf(ReaderVisibleItem(2, -100, 500)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )

        assertAll(
            { assertEquals(ReadingPosition(1, 100f / 600f), middle.position) },
            { assertFalse(middle.atEnd) },
            { assertEquals(ReadingPosition(2, 0.2f), bottom.position) },
            { assertTrue(bottom.atEnd) },
        )
    }

    @Test
    fun `a long final paragraph is not complete before its bottom reaches the viewport`() {
        // S16.3 completion boundary: inherited before C5, reproducible without any footer.
        val topOfParagraph = sampleReaderProgress(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 0,
            visible = listOf(ReaderVisibleItem(2, 0, 1200)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )
        val justOutsideTolerance = sampleReaderProgress(
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 791,
            visible = listOf(ReaderVisibleItem(2, -791, 1200)),
            viewportEndOffset = 400,
            lastIndex = 2,
        )

        assertAll(
            { assertEquals(ReadingPosition(2, 0f), topOfParagraph.position) },
            { assertFalse(topOfParagraph.atEnd, "The last paragraph still has 800 unread pixels below the viewport") },
            { assertEquals(ReadingPosition(2, 791f / 1200f), justOutsideTolerance.position) },
            { assertFalse(justOutsideTolerance.atEnd, "Nine unread pixels are outside the existing eight-pixel tolerance") },
        )
    }
}
