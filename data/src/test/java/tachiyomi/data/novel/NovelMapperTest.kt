package tachiyomi.data.novel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as NovelEntry

class NovelMapperTest {

    private val novel = NovelEntry(
        sourceId = "demo",
        id = "n1",
        title = "La torre de cristal",
        author = "Autor Uno",
        description = "Novela de prueba",
        coverUrl = "https://example.com/cover.jpg",
    )

    @Test
    fun `maps a novel onto the manga row contract`() {
        val manga = NovelMapper.toManga(novel)

        // remote_url is the remote id, and together with the source it is what makes the row unique.
        assertEquals("n1", manga.url)
        assertEquals("La torre de cristal", manga.title)
        assertEquals("Autor Uno", manga.author)
        assertEquals("Novela de prueba", manga.description)
        assertEquals("https://example.com/cover.jpg", manga.thumbnailUrl)
        assertEquals(NovelMapper.generateSourceId("demo"), manga.source)
    }

    @Test
    fun `derives a stable positive source id that differs per source`() {
        val id = NovelMapper.generateSourceId("demo")

        assertEquals(id, NovelMapper.generateSourceId("demo"))
        assertNotEquals(id, NovelMapper.generateSourceId("otro"))
        // The top bit is masked off, so the id can never collide with the -1 used for "no source".
        assertTrue(id >= 0)
    }

    @Test
    fun `maps a chapter onto the chapter row contract`() {
        val chapter = NovelMapper.toChapter(novel, NovelChapter("demo", "n1", "c1", "Capítulo 1", number = 1f))

        // remote_url holds the remote chapter id, not a web URL.
        assertEquals("c1", chapter.url)
        assertEquals("Capítulo 1", chapter.name)
        assertEquals(1.0, chapter.chapterNumber)
        assertTrue(chapter.isRecognizedNumber)
    }

    @Test
    fun `falls back to an unrecognized number when the source omits one`() {
        val chapter = NovelMapper.toChapter(novel, NovelChapter("demo", "n1", "c1", "Prólogo"))

        assertEquals(-1.0, chapter.chapterNumber)
        assertFalse(chapter.isRecognizedNumber)
    }

    @Test
    fun `keeps the web url in the memo so the source can resolve it later`() {
        val chapter = NovelMapper.toChapter(
            novel,
            NovelChapter("demo", "n1", "c1", "Capítulo 1", url = "https://example.com/c1"),
        )

        val url = NovelMapper.chapterUrl(chapter)
        assertEquals("https://example.com/c1", url)
        assertEquals("n1", NovelMapper.novelId(chapter))
        assertEquals("demo", NovelMapper.sourceId(chapter))
    }

    @Test
    fun `leaves a new manga and chapter unpersisted so the repository can assign ids`() {
        val chapter = NovelMapper.toChapter(novel, NovelChapter("demo", "n1", "c1", "Capítulo 1"))

        assertEquals(-1L, chapter.id)
        assertEquals(-1L, chapter.mangaId)
        assertFalse(chapter.read)
        assertEquals(0, chapter.lastPageRead)
    }

    @Test
    fun `preserves the listing order as the source order`() {
        val chapters = listOf(
            NovelChapter("demo", "n1", "c1", "Uno"),
            NovelChapter("demo", "n1", "c2", "Dos"),
            NovelChapter("demo", "n1", "c3", "Tres"),
        ).mapIndexed { index, chapter -> NovelMapper.toChapter(novel, chapter, sourceOrder = index.toLong()) }

        assertEquals(listOf(0L, 1L, 2L), chapters.map { it.sourceOrder })
        assertEquals(listOf("c1", "c2", "c3"), chapters.map { it.url })
    }

    @Test
    fun `carries the chapter listing back into the memo of each chapter`() {
        val memoChapters = listOf(
            NovelChapter("demo", "n1", "c1", "Uno", number = 1f),
            NovelChapter("demo", "n1", "c2", "Dos", number = 2f),
        )
        val mapped = memoChapters.map { NovelMapper.toChapter(novel, it) }

        assertEquals("Uno", mapped[0].name)
        assertEquals(2.0, mapped[1].chapterNumber)
        // The domain Manga/Chapter pair stays usable without importing novel-api at every call site.
        assertTrue(mapped.all { it.memo.isNotEmpty() })
    }

    @Test
    fun `maps a novel that carries no optional metadata`() {
        val sparse = NovelEntry(sourceId = "demo", id = "n2", title = "Sin metadatos")

        val manga = NovelMapper.toManga(sparse)

        assertEquals("n2", manga.url)
        assertEquals("Sin metadatos", manga.title)
        assertEquals(null, manga.author)
        assertEquals(null, manga.description)
        assertEquals(null, manga.thumbnailUrl)
    }

    @Test
    fun `marks a novel as initialized only when it carries details`() {
        val bare = NovelMapper.toManga(NovelEntry(sourceId = "demo", id = "n3", title = "Solo titulo"))
        val detailed = NovelMapper.toManga(novel)

        assertFalse(bare.initialized)
        assertTrue(detailed.initialized)
    }

    @Test
    fun `leaves a novel out of the library until the user adds it`() {
        val manga = NovelMapper.toManga(novel)

        // `favoriteAt` is what marks a row as library-owned; a freshly mapped novel has none.
        assertEquals(null, manga.favoriteAt)
        assertFalse(manga.favorite)
    }
}
