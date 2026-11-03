package tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.data.*
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.novel.NovelMapper
import tachiyomi.data.novel.ReadingProgressRepositoryImpl
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.track.repository.TrackRepository
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel

class RestoreRepositoryImplProgressRegressionTest {
    @Test
    fun `missing local progress retains chapter index and content without writing progress`() = runTest {
        withFixture(novel = true) { f ->
            f.restore(40)
            assertNull(f.progress.get(f.chapterId))
            assertEquals(0, f.progress.writes)
            assertEquals(20, f.chapters.getChapterById(f.chapterId)!!.lastPageRead)
            assertEquals(f.localContent, f.contents[f.chapterId])
        }
    }

    @Test
    fun `manga chapter still merges maximum without novel writes`() = runTest {
        withFixture(novel = false) { f ->
            f.restore(40)
            assertEquals(40, f.chapters.getChapterById(f.chapterId)!!.lastPageRead)
            assertEquals(0, f.progress.writes)
            assertEquals(f.localContent, f.contents[f.chapterId])
        }
    }

    @Test
    fun `backup advances local START without a content payload`() = runTest {
        withFixture(novel = true, localIndex = 0) { f ->
            f.restore(10, backupContent = null)
            assertEquals(ReadingProgress(10, 0, 0.8f, 0f), f.progress.get(f.chapterId))
            assertEquals(1, f.progress.writes)
            assertEquals(10, f.chapters.getChapterById(f.chapterId)!!.lastPageRead)
            assertEquals(f.localContent, f.contents[f.chapterId])
        }
    }

    @Test
    fun `backup advances local START despite a fingerprint contradicting decoded payload`() = runTest {
        withFixture(novel = true, localIndex = 0) { f ->
            val backup = ChapterContent(listOf(ContentBlock.Paragraph("backup")))
            f.restore(10, backupContent = backup, fingerprint = "v1:sha256:4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945")
            assertEquals(ReadingProgress(10, 0, 0.8f, 1f), f.progress.get(f.chapterId))
            assertEquals(1, f.progress.writes)
            assertEquals(10, f.chapters.getChapterById(f.chapterId)!!.lastPageRead)
            assertEquals(backup, f.contents[f.chapterId])
        }
    }

    @Test
    fun `missing backup fraction retains local index even with matching content`() = runTest {
        val local = ReadingProgress(20, 17, 0.3f, 0.4f)
        withFixture(novel = true, localProgress = local) { f ->
            f.restore(40, backupContent = f.localContent, fingerprint = ContentFingerprint.of(f.localContent), fraction = null)
            assertEquals(local, f.progress.get(f.chapterId))
            assertEquals(0, f.progress.writes)
            assertEquals(20, f.chapters.getChapterById(f.chapterId)!!.lastPageRead)
            assertEquals(f.localContent, f.contents[f.chapterId])
        }
    }

    private suspend fun withFixture(
        novel: Boolean,
        localIndex: Int = 20,
        localProgress: ReadingProgress? = null,
        block: suspend (Fixture) -> Unit,
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        try {
            val db = Database(
                driver = driver,
                chapterAdapter = Chapter.Adapter(
                    remote_date_uploadAdapter = InstantColumnAdapter,
                    remote_memoAdapter = MemoColumnAdapter,
                    user_last_page_readAdapter = IntColumnAdapter,
                    state_date_fetchAdapter = InstantColumnAdapter,
                ),
                chapter_contentAdapter = Chapter_content.Adapter(state_date_downloadAdapter = InstantColumnAdapter),
                historyAdapter = History.Adapter(read_atAdapter = InstantColumnAdapter),
                mangaAdapter = Manga.Adapter(
                    remote_genreAdapter = StringListColumnAdapter,
                    remote_update_strategyAdapter = UpdateStrategyColumnAdapter,
                    remote_memoAdapter = MemoColumnAdapter,
                    user_favorite_atAdapter = InstantColumnAdapter,
                    state_chapter_last_updateAdapter = InstantColumnAdapter,
                    state_chapter_next_updateAdapter = InstantColumnAdapter,
                    state_cover_last_modifiedAdapter = InstantColumnAdapter,
                ),
                manga_trackAdapter = Manga_track.Adapter(start_dateAdapter = InstantColumnAdapter, finish_dateAdapter = InstantColumnAdapter),
                reading_progressAdapter = Reading_progress.Adapter(
                    block_indexAdapter = IntColumnAdapter,
                    block_offset_pxAdapter = IntColumnAdapter,
                    state_date_updateAdapter = InstantColumnAdapter,
                ),
            )
            (Database.Schema.create(driver) as QueryResult.AsyncValue<Unit>).await()
            val n = Novel("fixture", "novel", "Fixture novel")
            val manga = NovelMapper.toManga(n)
            val original = NovelMapper.toChapter(n, NovelChapter("fixture", "novel", "chapter", "Chapter"))
            val chapter = if (novel) original else original.copy(memo = kotlinx.serialization.json.JsonObject(emptyMap()))
            val mangaId = db.mangaQueries.insertReturningId(
                sourceId = manga.source, remoteUrl = manga.url, remoteTitle = manga.title,
                remoteAuthor = manga.author, remoteArtist = manga.artist, remoteDescription = manga.description,
                remoteGenre = manga.genre, remoteStatus = manga.status, remoteCover = manga.thumbnailUrl,
                remoteUpdateStrategy = manga.updateStrategy, remoteMemo = manga.memo, userFavoriteAt = manga.favoriteAt,
                userNotes = manga.notes, userReaderFlags = manga.viewerFlags, userChapterFlags = manga.chapterFlags,
                stateChapterLastUpdate = manga.lastUpdate, stateChapterNextUpdate = manga.nextUpdate,
                stateChapterFetchInterval = manga.fetchInterval.toLong(), stateCoverLastModified = manga.coverLastModified,
                stateInitialized = manga.initialized,
            ).awaitAsOne()
            val chapterId = db.chapterQueries.insertReturningId(
                mangaId = mangaId, remoteUrl = chapter.url, remoteName = chapter.name,
                remoteScanlator = chapter.scanlator, remoteChapterNumber = chapter.chapterNumber,
                remoteDateUpload = chapter.dateUpload, remoteOrder = chapter.sourceOrder, remoteMemo = chapter.memo,
                userRead = chapter.read, userBookmark = chapter.bookmark, userLastPageRead = localIndex,
                stateDateFetch = chapter.dateFetch,
            ).awaitAsOne()
            val localContent = ChapterContent(listOf(ContentBlock.Paragraph("local")))
            val contents = mutableMapOf(chapterId to localContent)
            val contentRepo = mockk<ChapterContentRepository>()
            coEvery { contentRepo.get(any()) } coAnswers { contents[firstArg<Long>()] }
            coEvery { contentRepo.save(any(), any()) } coAnswers {
                contents[firstArg<Long>()] = secondArg<ChapterContent>()
                "fixture"
            }
            val progress = CountingProgress(ReadingProgressRepositoryImpl(db))
            if (localProgress != null) {
                progress.save(chapterId, localProgress)
                progress.writes = 0
            }
            val chapters = ChapterRepositoryImpl(db, contentRepo, progress)
            val mangas = mockk<MangaRepository>()
            coEvery { mangas.getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga.copy(id = mangaId)
            coEvery { mangas.update(any()) } returns true
            val tracks = mockk<TrackRepository>()
            coEvery { tracks.getTracksByMangaId(mangaId) } returns emptyList()
            val repository = RestoreRepositoryImpl(db, mangas, chapters, tracks, contentRepo, progress)
            block(Fixture(repository, chapters, progress, manga.copy(id = mangaId), chapter, chapterId, localContent, contents))
        } finally {
            driver.close()
        }
    }

    private class CountingProgress(private val delegate: ReadingProgressRepository) : ReadingProgressRepository by delegate {
        var writes = 0
        override suspend fun save(chapterId: Long, progress: ReadingProgress) {
            writes++
            delegate.save(chapterId, progress)
        }
    }

    private class Fixture(
        private val repository: RestoreRepositoryImpl,
        val chapters: ChapterRepositoryImpl,
        val progress: CountingProgress,
        private val manga: tachiyomi.domain.manga.model.Manga,
        private val chapter: tachiyomi.domain.chapter.model.Chapter,
        val chapterId: Long,
        val localContent: ChapterContent,
        val contents: MutableMap<Long, ChapterContent>,
    ) {
        suspend fun restore(
            index: Int,
            backupContent: ChapterContent? = ChapterContent(listOf(ContentBlock.Paragraph("incompatible"))),
            fingerprint: String? = null,
            fraction: Float? = 0.8f,
        ) {
            val entry = RestoredManga(
                manga = manga, chapters = listOf(chapter.copy(lastPageRead = index)),
                categoryIds = emptyList(), history = emptyList(), tracks = emptyList(), excludedScanlators = emptyList(),
                novelPositions = if (fraction == null) emptyMap() else mapOf(chapter.url to fraction),
                novelContents = if (backupContent == null) emptyMap() else
                    mapOf(chapter.url to Json.encodeToString(ChapterContent.serializer(), backupContent)),
                novelContentFingerprints = if (fingerprint == null) emptyMap() else mapOf(chapter.url to fingerprint),
            )
            repository.restoreManga(listOf(entry)) { MangaUpdate(it.id) {} }
        }
    }
}
