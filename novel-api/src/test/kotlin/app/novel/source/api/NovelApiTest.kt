package app.novel.source.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NovelApiTest {
    @Test
    fun `ChapterContent serializes and deserializes with all four block types`() {
        val content = ChapterContent(
            blocks = listOf(
                ContentBlock.Heading("Chapter 1", level = 1),
                ContentBlock.Paragraph("It was a bright cold day in April."),
                ContentBlock.Image(url = "https://example.com/img.jpg", alt = "An image"),
                ContentBlock.Divider,
                ContentBlock.Paragraph("The clocks were striking thirteen."),
            ),
        )

        val json = Json.encodeToString(ChapterContent.serializer(), content)
        val decoded = Json.decodeFromString(ChapterContent.serializer(), json)

        assertEquals(5, decoded.blocks.size)
        assertEquals(ContentBlock.Heading("Chapter 1", level = 1), decoded.blocks[0])
        assertEquals(ContentBlock.Paragraph("It was a bright cold day in April."), decoded.blocks[1])
        assertEquals(ContentBlock.Image(url = "https://example.com/img.jpg", alt = "An image"), decoded.blocks[2])
        assertEquals(ContentBlock.Divider, decoded.blocks[3])
        assertEquals(ContentBlock.Paragraph("The clocks were striking thirteen."), decoded.blocks[4])
    }

    @Test
    fun `NovelPage pagination works`() {
        val novel = Novel("demo", "n1", "Test Novel", author = "Author")
        val page = NovelPage(listOf(novel), hasNextPage = true)

        assertEquals(1, page.novels.size)
        assertTrue(page.hasNextPage)
        assertEquals("Test Novel", page.novels[0].title)
    }
}
