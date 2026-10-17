package app.novel.extension.matchanovels

import app.novel.source.api.Chapter
import app.novel.source.api.Novel
import app.novel.source.api.NovelNetwork
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The source as the host drives it: the network is a stub that serves the captured pages, so every
 * method is exercised end to end (URL in, contract types out) without a socket.
 */
class MatchaNovelsSourceTest {

    private class StubNetwork(private val pages: Map<String, String>) : NovelNetwork {
        override suspend fun fetch(url: String, headers: Map<String, String>): String =
            pages[url] ?: throw IllegalStateException("No stubbed page for $url")

        // MatchaNovels is text-only and never calls fetchBytes; throwing makes a future byte fetch in
        // this source fail loudly here instead of silently returning fabricated bytes.
        override suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray =
            throw UnsupportedOperationException("MatchaNovels does not fetch bytes")
    }

    private fun source(pages: Map<String, String> = emptyMap()): MatchaNovelsSource =
        MatchaNovelsSource().apply { bindNetwork(StubNetwork(pages)) }

    private val catalogueUrl = "${TestPages.SITE}/biblioteca"
    private val novelUrl = "${TestPages.SITE}/novela/I-Married-The-Dragon-I-Killed"
    private val chapterUrl = "$novelUrl/capitulo/1"

    private val novel = Novel(sourceId = "matchanovels", id = "I-Married-The-Dragon-I-Killed", title = "stale")

    @Test
    fun `popular reads the catalogue and there is never a next page`() = runBlocking {
        val source = source(mapOf(catalogueUrl to TestPages.CATALOGUE))

        val first = source.popular(1)
        assertEquals(3, first.novels.size)
        assertFalse(first.hasNextPage)

        val second = source.popular(2)
        assertTrue(second.novels.isEmpty())
        assertFalse(second.hasNextPage)
    }

    @Test
    fun `search filters the catalogue and never pages`() = runBlocking {
        val source = source(mapOf(catalogueUrl to TestPages.CATALOGUE))

        assertEquals(listOf("Corazon-de-Tinta"), source.search("corazon", 1).novels.map { it.id })
        assertTrue(source.search("corazon", 2).novels.isEmpty())
    }

    @Test
    fun `getNovelDetails merges the page metadata into the novel`() = runBlocking {
        val source = source(mapOf(novelUrl to TestPages.NOVEL))

        val details = source.getNovelDetails(novel)
        assertEquals("I Married The Dragon I Killed", details.title)
        assertEquals("MatchaNovels", details.author)
        assertEquals(
            "${TestPages.SITE}/static/imagenes-portadas/portada-I-Married-The-Dragon-I-Killed.webp?v=1784448982",
            details.coverUrl,
        )
    }

    @Test
    fun `getChapters returns the real, numbered chapters with absolute urls`() = runBlocking {
        val source = source(mapOf(novelUrl to TestPages.NOVEL))

        val chapters = source.getChapters(novel)
        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals(chapterUrl, chapters.first().url)
    }

    @Test
    fun `getChapters prefers the flight payload over the truncated anchors`() = runBlocking {
        val rows = (1..500).map { TestPages.chapterRow(it.toString()) }
        val page = TestPages.payloadNovel(novel.id, rows, escaped = true, links = 3)
        val source = source(mapOf(novelUrl to page))

        val chapters = source.getChapters(novel)

        assertEquals(500, chapters.size)
        assertEquals("${TestPages.SITE}/novela/${novel.id}/capitulo/500", chapters.last().url)
    }

    @Test
    fun `getChapterContent renders the prose paragraphs`() = runBlocking {
        val source = source(mapOf(chapterUrl to TestPages.CHAPTER))
        val chapter = Chapter(
            sourceId = "matchanovels",
            novelId = novel.id,
            id = "1",
            title = "Capítulo 1",
            number = 1f,
            url = chapterUrl,
        )

        val content = source.getChapterContent(chapter)
        assertEquals(3, content.blocks.size)
    }

    @Test
    fun `a chapter with no url fails without a request`() {
        val source = source()
        val chapter = Chapter(sourceId = "matchanovels", novelId = novel.id, id = "1", title = "Capítulo 1")

        assertThrows(IllegalStateException::class.java) { runBlocking { source.getChapterContent(chapter) } }
    }

    @Test
    fun `a chapter page that parses to nothing fails instead of reading as empty`() {
        val source = source(mapOf(chapterUrl to TestPages.CHAPTER_WITHOUT_PROSE))
        val chapter = Chapter(
            sourceId = "matchanovels",
            novelId = novel.id,
            id = "1",
            title = "Capítulo 1",
            url = chapterUrl,
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { source.getChapterContent(chapter) } }
    }
}
