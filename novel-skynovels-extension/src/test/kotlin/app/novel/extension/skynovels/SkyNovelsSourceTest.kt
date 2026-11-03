package app.novel.extension.skynovels

import app.novel.source.api.Chapter
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The source as the host drives it: the network is a stub that serves the captured responses, so every
 * method is exercised end to end (URL in, contract types out) without a socket.
 */
class SkyNovelsSourceTest {

    private class StubNetwork(private val responses: Map<String, String>) : NovelNetwork {
        override suspend fun fetch(url: String, headers: Map<String, String>): String =
            responses[url] ?: throw IllegalStateException("No stubbed response for $url")

        // SkyNovels is text-only and never calls fetchBytes; throwing makes a future byte fetch in this
        // source fail loudly here instead of silently returning fabricated bytes.
        override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray =
            throw UnsupportedOperationException("SkyNovels does not fetch bytes")
    }

    private fun source(responses: Map<String, String> = emptyMap()): SkyNovelsSource =
        SkyNovelsSource().apply { bindNetwork(StubNetwork(responses)) }

    private val novel = Novel(sourceId = "skynovels", id = "866", title = "El Dominio del Emperador")

    @Test
    fun `popular reads the catalogue page and reports the next one`() = runBlocking {
        val source = source(mapOf(TestFixtures.CATALOGUE_URL to TestFixtures.CATALOGUE))

        val first = source.popular(1)
        assertEquals(2, first.novels.size)
        assertTrue(first.hasNextPage)
    }

    @Test
    fun `popular refuses a page below the first`() {
        val source = source()

        assertThrows(IllegalArgumentException::class.java) { runBlocking { source.popular(0) } }
    }

    @Test
    fun `search reads the q endpoint`() = runBlocking {
        val source = source(mapOf(TestFixtures.SEARCH_URL to TestFixtures.SEARCH))

        assertEquals(listOf("7"), source.search("dragon", 1).novels.map { it.id })
    }

    @Test
    fun `getNovelDetails merges the base response into the novel`() = runBlocking {
        val source = source(mapOf(TestFixtures.BASE_URL to TestFixtures.NOVEL_BASE))

        val details = source.getNovelDetails(novel)
        assertEquals("El Dominio del Emperador", details.title)
        assertEquals("Yan Bi Xiao Sheng", details.author)
    }

    @Test
    fun `getChapters returns the full numbered list`() = runBlocking {
        val source = source(mapOf(TestFixtures.CHAPTER_LIST_URL to TestFixtures.CHAPTER_LIST))

        val chapters = source.getChapters(novel)
        assertEquals(3, chapters.size)
        val firstNumber = chapters.first().number ?: 0f
        assertEquals(355958f, firstNumber)
    }

    @Test
    fun `getChapters fails when the api returns fewer chapters than declared`() {
        val truncated = TestFixtures.chapterList(declared = 5, ids = listOf(1, 2, 3))
        val source = source(mapOf(TestFixtures.CHAPTER_LIST_URL to truncated))

        assertThrows(IllegalStateException::class.java) { runBlocking { source.getChapters(novel) } }
    }

    @Test
    fun `getChapterContent renders the prose with the watermark removed`() = runBlocking {
        val source = source(mapOf(TestFixtures.CHAPTER_URL to TestFixtures.CHAPTER_MARKDOWN))

        val content = source.getChapterContent(chapter())
        assertEquals(
            listOf(
                ContentBlock.Heading("Capítulo 1: El comienzo", level = 2),
                ContentBlock.Paragraph("Había una vez un hombre singular. Su nombre era Ferda."),
                ContentBlock.Divider,
                ContentBlock.Paragraph("Fin."),
            ),
            content.blocks,
        )
        assertTrue(content.blocks.none { it is ContentBlock.Paragraph && it.text.contains('\u200b') })
    }

    @Test
    fun `a chapter that parses to nothing fails instead of reading as empty`() {
        val source = source(mapOf(TestFixtures.CHAPTER_URL to TestFixtures.CHAPTER_INVISIBLE_ONLY))

        assertThrows(IllegalStateException::class.java) { runBlocking { source.getChapterContent(chapter()) } }
    }

    @Test
    fun `getChapterContent preserves HTML comparisons through the source API`() = runBlocking {
        val response = """{"chapter":{"chp_content":"<p title='x > y'>Si a < b y c > d. <em>Fin.</em></p>"}}"""
        val content = source(mapOf(TestFixtures.CHAPTER_URL to response)).getChapterContent(chapter())
        assertEquals(listOf(ContentBlock.Paragraph("Si a < b y c > d. Fin.")), content.blocks)
    }

    private fun chapter() = Chapter(
        sourceId = "skynovels",
        novelId = "866",
        id = "355958",
        title = "Capítulo 355958",
    )
}
