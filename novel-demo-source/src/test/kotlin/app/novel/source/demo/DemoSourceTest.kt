package app.novel.source.demo

import app.novel.source.api.ContentBlock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoSourceTest {
    private val source = DemoSource()

    @Test
    fun `popular returns novels on first page`() = runBlocking {
        val page = source.popular(1)
        assertEquals(2, page.novels.size)
        assertEquals("La torre de cristal", page.novels[0].title)
    }

    @Test
    fun `popular returns empty on second page`() = runBlocking {
        val page = source.popular(2)
        assertTrue(page.novels.isEmpty())
    }

    @Test
    fun `search filters by title`() = runBlocking {
        val page = source.search("torre", 1)
        assertEquals(1, page.novels.size)
        assertEquals("La torre de cristal", page.novels[0].title)
    }

    @Test
    fun `search only returns filtered catalogue on the first page`() = runBlocking {
        val first = source.search("TORRE", 1)
        assertEquals(listOf("La torre de cristal"), first.novels.map { it.title })
        assertEquals(false, first.hasNextPage)
        assertEquals(source.popular(1).novels, source.search("", 1).novels)

        for (pageNumber in listOf(0, 2, 3)) {
            val page = source.search("torre", pageNumber)
            assertTrue(page.novels.isEmpty(), "page $pageNumber")
            assertEquals(false, page.hasNextPage)
        }
    }

    @Test
    fun `getChapters returns 5 chapters`() = runBlocking {
        val novel = source.popular(1).novels[0]
        val chapters = source.getChapters(novel)
        assertEquals(5, chapters.size)
        assertEquals("Capítulo 1", chapters[0].title)
        assertEquals(1f, chapters[0].number)
    }

    @Test
    fun `getChapterContent is deterministic`() = runBlocking {
        val novel = source.popular(1).novels[0]
        val chapters = source.getChapters(novel)
        val content1 = source.getChapterContent(chapters[0])
        val content2 = source.getChapterContent(chapters[0])

        assertEquals(content1, content2)
        assertEquals(41, content1.blocks.size) // 1 heading + 40 paragraphs
        assertTrue(content1.blocks[0] is ContentBlock.Heading)
        assertTrue(content1.blocks[1] is ContentBlock.Paragraph)
    }
}
