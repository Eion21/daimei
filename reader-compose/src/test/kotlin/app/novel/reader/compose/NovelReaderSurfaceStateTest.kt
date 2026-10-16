package app.novel.reader.compose

import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of the reader's three states a composition is in.
 *
 * The mistake this pins is the one that shipped: a chapter that failed and a chapter still being opened both arrived
 * as the same null content, and the reader drew a spinner for either, so an impossible read spun for as long as the
 * screen was open. Deciding it here rather than inline in the composable is what makes that difference assertable
 * without a device.
 */
class NovelReaderSurfaceStateTest {

    private val content = ChapterContent(blocks = listOf(ContentBlock.Paragraph("It was a bright cold day in April.")))

    @Test
    fun `a chapter in hand is the content state`() {
        assertEquals(
            ReaderSurfaceState.Content(content),
            readerSurfaceStateFor(content = content, hasError = false),
        )
    }

    @Test
    fun `a chapter that failed is the error state and not the spinner`() {
        // The regression itself: these two arrive as the same null content, and only the failure tells them apart.
        assertEquals(ReaderSurfaceState.Error, readerSurfaceStateFor(content = null, hasError = true))
    }

    @Test
    fun `nothing published yet is the loading state`() {
        assertEquals(ReaderSurfaceState.Loading, readerSurfaceStateFor(content = null, hasError = false))
    }

    @Test
    fun `a chapter in hand outranks an error left behind`() {
        // Defence in depth rather than a reachable state: content is what the reader can use, so a failure that
        // outlived it must never cover it.
        assertEquals(
            ReaderSurfaceState.Content(content),
            readerSurfaceStateFor(content = content, hasError = true),
        )
    }

    @Test
    fun `a chapter with no blocks is the error state and not a blank page`() {
        // `ChapterBody` draws one item per block, so a chapter with none is an empty LazyColumn: a black page
        // that looks like the reader is broken. Zero blocks is the absence of a chapter, and the surface says so
        // even when nobody upstream has published a failure yet.
        assertEquals(
            ReaderSurfaceState.Error,
            readerSurfaceStateFor(content = ChapterContent(blocks = emptyList()), hasError = false),
        )
    }
}
