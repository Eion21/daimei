package eu.kanade.tachiyomi.source.novel

import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.ChapterContent
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import app.novel.source.demo.DemoSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.novel.NovelMapper
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.model.Source as DomainSource

class NovelSourceAdapterTest {

    private val adapter = NovelSourceAdapter(DemoSource())

    @Test
    fun `id matches NovelMapper generateSourceId so an existing library keeps pointing at this source`() {
        assertEquals(NovelMapper.generateSourceId("demo"), adapter.id)
    }

    @Test
    fun `name and lang come from the novel source info`() {
        assertEquals("Demo", adapter.name)
        assertEquals("es", adapter.lang)
    }

    @Test
    fun `getPopularManga maps every novel and uses the novel id as the remote url`() = runBlocking {
        val page = adapter.getPopularManga(1)

        assertEquals(listOf("n1", "n2"), page.mangas.map { it.url })
        assertEquals(listOf("La torre de cristal", "Crónicas del valle"), page.mangas.map { it.title })
        assertEquals(listOf("Autor Uno", "Autor Dos"), page.mangas.map { it.author })
        assertEquals(false, page.hasNextPage)
    }

    @Test
    fun `getPopularManga on a later page returns nothing`() = runBlocking {
        assertEquals(0, adapter.getPopularManga(2).mangas.size)
    }

    @Test
    fun `getLatestUpdates returns the popular listing because the novel contract has no latest concept`() = runBlocking {
        assertEquals(adapter.getPopularManga(1).mangas.map { it.url }, adapter.getLatestUpdates(1).mangas.map { it.url })
    }

    @Test
    fun `getSearchManga delegates the query and filters the results`() = runBlocking {
        assertEquals(listOf("n2"), adapter.getSearchManga(1, "valle", FilterList()).mangas.map { it.url })
        assertEquals(0, adapter.getSearchManga(1, "nothing matches this", FilterList()).mangas.size)
    }

    @Test
    fun `getMangaUpdate returns the details and the chapters`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)

        assertEquals("Novela de prueba con contenido determinista.", update.manga.description)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), update.chapters.map { it.url })
        assertEquals(listOf("Capítulo 1", "Capítulo 5"), listOf(update.chapters.first().name, update.chapters.last().name))
        assertEquals(listOf(1f, 5f), listOf(update.chapters.first().chapter_number, update.chapters.last().chapter_number))
    }

    @Test
    fun `getMangaUpdate keeps the manga untouched when details are not requested`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)

        assertEquals(manga.title, update.manga.title)
        assertNull(update.manga.description)
        assertEquals(5, update.chapters.size)
    }

    @Test
    fun `getMangaUpdate returns no chapters when chapters are not requested`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val update = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)

        assertEquals(0, update.chapters.size)
    }

    @Test
    fun `a chapter with no number maps to -1`() = runBlocking {
        val fixture = NovelSourceAdapter(FixtureSource())
        val manga = fixture.getPopularManga(1).mangas.first()

        val chapter = fixture.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).chapters.single()

        assertEquals(-1f, chapter.chapter_number)
    }

    @Test
    fun `chapter memo carries the ids the text reader needs and can be read back`() = runBlocking {
        val fixture = NovelSourceAdapter(FixtureSource())
        val manga = fixture.getPopularManga(1).mangas.first()

        val chapter = fixture.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).chapters.single()
        val memo = NovelMapper.toChapter(FixtureSource.novel, FixtureSource.chapter).memo

        assertEquals(FixtureSource.sourceInfo.id, memo["novelSourceId"]?.jsonPrimitive?.content)
        assertEquals("novel-42", memo["novelId"]?.jsonPrimitive?.content)
        assertEquals("https://example.test/ch-1", memo["novelChapterUrl"]?.jsonPrimitive?.content)

        // The adapter writes the same keys, so whatever the text reader reads back agrees with the database.
        assertEquals(memo, chapter.memo)
    }

    @Test
    fun `chapter memo omits the chapter url when the novel source does not provide one`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()

        val chapter = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).chapters.first()

        assertNull(chapter.memo["novelChapterUrl"])
        assertEquals("demo", chapter.memo["novelSourceId"]?.jsonPrimitive?.content)
        assertEquals("n1", chapter.memo["novelId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getPageList explains that novel text does not go through the page list`() = runBlocking {
        val manga = adapter.getPopularManga(1).mangas.first()
        val chapter = adapter.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).chapters.first()

        val error = runCatching { adapter.getPageList(chapter) }.exceptionOrNull()

        assertTrue(error is UnsupportedOperationException, "expected UnsupportedOperationException, got $error")
        val message = checkNotNull(checkNotNull(error).message)
        assertTrue(message.contains("text reader"), "message should point at the text reader, was: $message")
    }

    @Test
    fun `adapter urls and memos are the same strings NovelMapper writes into the database`() = runBlocking {
        val page = adapter.getPopularManga(1)
        val listingNovel = Novel("demo", "n1", "La torre de cristal", author = "Autor Uno")
        val update = adapter.getMangaUpdate(page.mangas.first(), emptyList(), fetchDetails = true, fetchChapters = true)
        val firstChapter = NovelChapter("demo", "n1", "c1", "Capítulo 1", number = 1f)

        val persistedManga = NovelMapper.toManga(listingNovel)
        val persistedChapter = NovelMapper.toChapter(listingNovel, firstChapter)

        // `source_id` + `remote_url` is what makes a manga row unique, so a drift here silently orphans the library.
        assertEquals(NovelMapper.generateSourceId("demo"), persistedManga.source)
        assertEquals(adapter.id, persistedManga.source)
        assertEquals(persistedManga.url, page.mangas.first().url)
        assertEquals(persistedManga.title, page.mangas.first().title)
        assertEquals(persistedManga.author, page.mangas.first().author)
        assertEquals(persistedManga.memo, page.mangas.first().memo)

        assertEquals(persistedChapter.url, update.chapters.first().url)
        assertEquals(persistedChapter.name, update.chapters.first().name)
        assertEquals(persistedChapter.memo, update.chapters.first().memo)
    }

    @Test
    fun `two novel sources produce distinct ids so both can sit in the seed map at once`() {
        val fixture = NovelSourceAdapter(FixtureSource())

        assertEquals(NovelMapper.generateSourceId("fixture"), fixture.id)
        assertNotEquals(adapter.id, fixture.id)
    }

    @Test
    fun `isNovelSource recognizes an adapter and rejects any other source`() {
        val notANovelSource: Source = StubSource(id = adapter.id, lang = "en", name = "Something else")

        assertTrue(adapter.isNovelSource())
        assertFalse(notANovelSource.isNovelSource())
    }

    @Test
    fun `the domain source helper matches against the ids the adapters expose`() {
        val novelSourceIds = setOf(adapter.id)
        val novel = DomainSource(id = adapter.id, lang = "es", name = "Demo", supportsLatest = true, isStub = false)

        assertTrue(novel.isNovelSource(novelSourceIds))
        assertFalse(novel.isNovelSource(emptySet()))
        assertFalse(DomainSource(1L, "en", "Other", true, false).isNovelSource(novelSourceIds))
    }
}

/**
 * [DemoSource] only produces chapters that carry a number and no web url, so the adapter's handling of a
 * missing number and of a missing chapter url needs a source that exercises both.
 */
private class FixtureSource : NovelSource {

    override val info = sourceInfo

    override suspend fun popular(page: Int) = NovelPage(listOf(novel), hasNextPage = false)

    override suspend fun search(query: String, page: Int) = NovelPage(listOf(novel), hasNextPage = false)

    override suspend fun getNovelDetails(novel: Novel) = novel.copy(description = "Fixture details")

    override suspend fun getChapters(novel: Novel) = listOf(chapter)

    override suspend fun getChapterContent(chapter: NovelChapter) = ChapterContent(emptyList())

    companion object {
        val sourceInfo = SourceInfo("fixture", "Fixture", "1", "en")
        val novel = Novel(sourceInfo.id, "novel-42", "Fixture novel", author = "Autor")
        val chapter = NovelChapter(
            sourceId = sourceInfo.id,
            novelId = novel.id,
            id = "ch-1",
            title = "Prólogo",
            number = null,
            url = "https://example.test/ch-1",
        )
    }
}
