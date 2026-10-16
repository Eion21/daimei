package tachiyomi.domain.novel.interactor

import app.novel.runtime.NovelSourceId
import app.novel.runtime.SourceRegistry
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import app.novel.source.api.Novel
import app.novel.source.api.NovelPage
import app.novel.source.api.NovelSource
import app.novel.source.api.SourceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterSummary
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import app.novel.source.api.Chapter as NovelChapter

/**
 * The cache-then-source contract, with the source being the point.
 *
 * A test that only ever served content from disk would have passed while the whole fallback was missing and every
 * uncached chapter showed a black screen forever, so the assertions here are about which side of the exchange each
 * call went to: the source is not consulted for content it already handed over, and is consulted exactly once for
 * content it has not.
 */
class GetChapterContentTest {

    private val content = ChapterContent(
        blocks = listOf(
            ContentBlock.Heading("Capítulo 1", level = 1),
            ContentBlock.Paragraph("It was a bright cold day in April."),
        ),
    )

    /** Counts the calls a test needs to prove a source was, or was not, asked. */
    private class RecordingSource(
        override val info: SourceInfo,
        private val content: ChapterContent,
        private val failure: Throwable? = null,
    ) : NovelSource {
        val requested = mutableListOf<NovelChapter>()

        override suspend fun popular(page: Int) = NovelPage(emptyList(), hasNextPage = false)

        override suspend fun search(query: String, page: Int) = NovelPage(emptyList(), hasNextPage = false)

        override suspend fun getNovelDetails(novel: Novel) = novel

        override suspend fun getChapters(novel: Novel) = emptyList<NovelChapter>()

        override suspend fun getChapterContent(chapter: NovelChapter): ChapterContent {
            requested += chapter
            failure?.let { throw it }
            return content
        }
    }

    /** Disk, keyed by chapter id, so a test can assert both what was cached and what was asked for. */
    private class FakeContentRepository : ChapterContentRepository {
        val cached = mutableMapOf<Long, ChapterContent>()
        val saved = mutableListOf<Pair<Long, ChapterContent>>()
        var getCount = 0
            private set

        override suspend fun get(chapterId: Long): ChapterContent? {
            getCount++
            return cached[chapterId]
        }

        override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = emptyFlow()

        override suspend fun save(chapterId: Long, content: ChapterContent): String {
            saved += chapterId to content
            cached[chapterId] = content
            return "/tmp/chapter_$chapterId.json"
        }

        override suspend fun delete(chapterId: Long) {
            cached -= chapterId
        }

        // The per-manga cached set is the chapter list's business, not this interactor's: nothing here ever
        // asks for it. Spelled out rather than defaulted on the interface so a fake that needs the behaviour has
        // to say so.
        override fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>> = emptyFlow()
    }

    private class FakeChapterRepository(private val chapters: Map<Long, Chapter>) : ChapterRepository {
        override suspend fun getChapterById(id: Long): Chapter? = chapters[id]
        override suspend fun update(chapterUpdate: ChapterUpdate) = Unit
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) = Unit
        override suspend fun updateFromRemote(
            removedIds: List<Long>,
            added: List<Chapter>,
            updated: List<ChapterRemoteUpdate>,
        ): List<Chapter> = emptyList()

        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean) = emptyList<Chapter>()
        override suspend fun getChapterSummaryByMangaId(mangaId: Long): ChapterSummary = throw NotImplementedError()
        override suspend fun getScanlatorsByMangaId(mangaId: Long) = emptyList<String>()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = emptyFlow()
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long) = emptyList<Chapter>()
        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? = null
        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = emptyFlow()
    }

    /** Only [getMangaById] is ever reached; the rest would mean the fallback read more than it needs. */
    private class FakeMangaRepository(private val manga: Map<Long, Manga>) : MangaRepository {
        var getMangaByIdCalls = 0
            private set

        override suspend fun getMangaById(id: Long): Manga {
            getMangaByIdCalls++
            return manga[id] ?: error("Manga $id is gone")
        }

        override fun getMangaByIdAsFlow(id: Long): Flow<Manga> = emptyFlow()
        override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? = null
        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> = emptyFlow()
        override suspend fun getFavorites() = emptyList<Manga>()
        override suspend fun getReadMangaNotInLibrary() = emptyList<Manga>()
        override suspend fun getLibraryManga() = emptyList<LibraryManga>()
        override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = emptyFlow()
        override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = emptyFlow()
        override suspend fun getDuplicateLibraryManga(id: Long, title: String) = emptyList<MangaWithChapterCount>()
        override suspend fun getUpcomingManga(
            statuses: Set<Long>,
            excludedCategories: List<Long>,
            includedCategories: List<Long>,
        ): Flow<List<Manga>> = emptyFlow()

        override suspend fun resetViewerFlags() = false
        override suspend fun deleteNonLibraryManga(sourceIds: List<Long>, keepReadManga: Boolean) = Unit
        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) = Unit
        override suspend fun getExcludedScanlators(mangaId: Long): Set<String> = emptySet()
        override fun getExcludedScanlatorsAsFlow(mangaId: Long): Flow<Set<String>> = emptyFlow()
        override suspend fun setExcludedScanlators(mangaId: Long, excludedScanlators: Set<String>) = Unit
        override suspend fun update(update: MangaUpdate) = false
        override suspend fun updateRemote(update: MangaRemoteUpdate) = false
        override suspend fun updateAll(mangaUpdates: List<MangaUpdate>) = false
        override suspend fun insertNetworkManga(manga: List<Manga>) = emptyList<Manga>()
    }

    private val registry = SourceRegistry()

    private fun chapterWithMemo(
        id: Long,
        mangaId: Long,
        memo: kotlinx.serialization.json.JsonObject,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = CHAPTER_REMOTE_ID,
        name = "Capítulo 1",
        chapterNumber = 1.0,
        memo = memo,
    )

    private fun interactor(
        contentRepository: ChapterContentRepository,
        chapters: Map<Long, Chapter> = emptyMap(),
        manga: FakeMangaRepository = FakeMangaRepository(emptyMap()),
    ) = GetChapterContent(contentRepository, FakeChapterRepository(chapters), manga, registry)

    @Test
    fun `content already on disk is served without asking the source`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository().apply { cached[CHAPTER_ID] = content }
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        val result = interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID)

        assertEquals(content, result)
        assertTrue(source.requested.isEmpty(), "the source was consulted for content already cached")
    }

    @Test
    fun `content that is not cached is fetched from the source and then saved`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        val result = interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID)

        assertEquals(content, result)
        assertEquals(listOf(CHAPTER_ID to content), repository.saved)
        assertEquals(1, source.requested.size)
    }

    @Test
    fun `the source is asked for the chapter the memo describes`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)

        interactor(FakeContentRepository(), mapOf(CHAPTER_ID to chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())))
            .await(CHAPTER_ID)

        val requested = source.requested.single()
        assertEquals("demo", requested.sourceId)
        assertEquals(NOVEL_ID, requested.novelId)
        assertEquals(CHAPTER_REMOTE_ID, requested.id)
        assertEquals(CHAPTER_WEB_URL, requested.url)
    }

    @Test
    fun `a second read is served from the cache without asking the source again`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())
        val getChapterContent = interactor(repository, mapOf(CHAPTER_ID to chapter))

        assertEquals(content, getChapterContent.await(CHAPTER_ID))
        assertEquals(content, getChapterContent.await(CHAPTER_ID))

        assertEquals(1, source.requested.size, "the source was asked twice for the same chapter")
    }

    @Test
    fun `a chapter with no memo falls back to the manga row`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, kotlinx.serialization.json.JsonObject(emptyMap()))
        val manga = FakeMangaRepository(
            mapOf(
                MANGA_ID to Manga.create().copy(
                    id = MANGA_ID,
                    source = NovelSourceId.generate("demo"),
                    url = NOVEL_ID,
                ),
            ),
        )

        val result = interactor(repository, mapOf(CHAPTER_ID to chapter), manga).await(CHAPTER_ID)

        assertEquals(content, result)
        assertEquals(listOf(CHAPTER_ID to content), repository.saved)
        assertEquals(NOVEL_ID, source.requested.single().novelId)
    }

    @Test
    fun `a memo without a novel id also falls back to the manga row`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        // `novelSourceId` is present, which is what proves the row came from a novel source, but there is no
        // `novelId` to ask the source about without reading the manga.
        val chapter = chapterWithMemo(
            CHAPTER_ID,
            MANGA_ID,
            buildJsonObject { put("novelSourceId", JsonPrimitive("demo")) },
        )
        val manga = FakeMangaRepository(
            mapOf(
                MANGA_ID to Manga.create().copy(
                    id = MANGA_ID,
                    source = NovelSourceId.generate("demo"),
                    url = NOVEL_ID,
                ),
            ),
        )

        assertEquals(content, interactor(repository, mapOf(CHAPTER_ID to chapter), manga).await(CHAPTER_ID))
        assertEquals(NOVEL_ID, source.requested.single().novelId)
    }

    @Test
    fun `a chapter row that is gone resolves to nothing and never reaches a source`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)

        val result = interactor(FakeContentRepository()).await(CHAPTER_ID)

        assertNull(result)
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun `a chapter no registered novel source can serve resolves to nothing`() = runTest {
        // Nothing registered at all: the memo names a source this build does not ship.
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        assertNull(interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a memo naming a source that is not registered resolves to nothing`() = runTest {
        RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(
            CHAPTER_ID,
            MANGA_ID,
            buildJsonObject {
                put("novelSourceId", JsonPrimitive("removed-source"))
                put("novelId", JsonPrimitive(NOVEL_ID))
            },
        )
        // The manga row would resolve, so a fallback that ignored the memo would wrongly succeed.
        val manga = FakeMangaRepository(
            mapOf(
                MANGA_ID to Manga.create().copy(
                    id = MANGA_ID,
                    source = NovelSourceId.generate("demo"),
                    url = NOVEL_ID,
                ),
            ),
        )

        assertNull(interactor(repository, mapOf(CHAPTER_ID to chapter), manga).await(CHAPTER_ID))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a source that throws does not take the reader down with it`() = runTest {
        RecordingSource(demoInfo, content, failure = IllegalStateException("boom")).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        assertNull(interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID))
        // A failure must not leave a half-written entry behind for the next read to trust.
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a source that answers with no blocks is a miss and is never cached`() = runTest {
        RecordingSource(demoInfo, ChapterContent(blocks = emptyList())).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        // The device held `{"blocks":[]}` for three chapters, all written at the same moment, offline. There is
        // no readable content in any of them, and the cache answered before the source was ever asked again —
        // which is what made one cut connection permanently unreadable. An empty chapter is a failure, not a
        // chapter: nothing to read means nothing worth storing.
        assertNull(interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID))
        assertTrue(repository.saved.isEmpty(), "an empty chapter was written to the content cache")
    }

    @Test
    fun `an empty chapter already in the cache is evicted and fetched again`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, memo())

        // The entry a device already holds, written before `fetch` learned to refuse an empty answer. Answering
        // from it would leave the chapter unreadable forever, because the cache is consulted before the source on
        // every read and no retry could ever get past it. Evicting it is what lets the fix reach a chapter that
        // was already poisoned instead of only chapters that fail from here on.
        repository.cached[CHAPTER_ID] = ChapterContent(blocks = emptyList())

        val result = interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID)

        assertNotNull(result, "the poisoned entry was trusted instead of being refetched")
        assertEquals(content, result)
        assertTrue(CHAPTER_ID !in repository.cached || repository.cached[CHAPTER_ID] == content) {
            "the empty entry was left behind"
        }
        assertEquals(listOf(CHAPTER_ID to content), repository.saved)
    }

    @Test
    fun `a chapter that is not a novel never reaches a novel source`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        // An image chapter: no memo, and a manga row that belongs to no novel source.
        val chapter = Chapter.create().copy(id = CHAPTER_ID, mangaId = MANGA_ID, url = "/chapter-1")
        val manga = FakeMangaRepository(
            mapOf(MANGA_ID to Manga.create().copy(id = MANGA_ID, source = -1L, url = "/manga")),
        )

        assertNull(interactor(repository, mapOf(CHAPTER_ID to chapter), manga).await(CHAPTER_ID))
        assertTrue(source.requested.isEmpty())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a memo survives the round trip the database does`() = runTest {
        val source = RecordingSource(demoInfo, content).also(registry::register)
        val repository = FakeContentRepository()
        // What `ChapterRepositoryImpl` hands back is the stored JSON, not the object that was written: the memo
        // has to be read as a parsed `JsonObject`, which is what this shape is.
        val storedMemo = buildJsonObject {
            put("novelSourceId", JsonPrimitive("demo"))
            put("novelId", JsonPrimitive(NOVEL_ID))
            put("novelChapterUrl", JsonPrimitive(CHAPTER_WEB_URL))
            put("mihon.unrelated", JsonPrimitive("ignored"))
        }
        val chapter = chapterWithMemo(CHAPTER_ID, MANGA_ID, storedMemo)

        val result = interactor(repository, mapOf(CHAPTER_ID to chapter)).await(CHAPTER_ID)

        assertNotNull(result)
        assertEquals(CHAPTER_WEB_URL, source.requested.single().url)
    }

    private fun memo() = buildJsonObject {
        put("novelSourceId", JsonPrimitive("demo"))
        put("novelId", JsonPrimitive(NOVEL_ID))
        put("novelChapterUrl", JsonPrimitive(CHAPTER_WEB_URL))
    }

    private companion object {
        val demoInfo = SourceInfo("demo", "Demo", "1", "es")

        const val CHAPTER_ID = 7L
        const val MANGA_ID = 3L
        const val NOVEL_ID = "n1"
        const val CHAPTER_REMOTE_ID = "c1"
        const val CHAPTER_WEB_URL = "https://example.com/c1"
    }
}
