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
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapter
import tachiyomi.data.Chapter_content
import tachiyomi.data.Database
import tachiyomi.data.History
import tachiyomi.data.InstantColumnAdapter
import tachiyomi.data.IntColumnAdapter
import tachiyomi.data.Manga
import tachiyomi.data.Manga_track
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Reading_progress
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.novel.NovelMapper
import tachiyomi.data.novel.ReadingProgressRepositoryImpl
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.security.MessageDigest
import app.novel.source.api.Chapter as NovelChapter
import app.novel.source.api.Novel as Novel

// Fixture identity only; this does not verify the future production canonicalizer.
private fun fixtureFingerprint(content: ChapterContent): String {
    val blocks = Json.encodeToJsonElement(ChapterContent.serializer(), content).jsonObject.getValue("blocks")
    val digest = MessageDigest.getInstance("SHA-256").digest(blocks.toString().toByteArray(Charsets.UTF_8))
    return "v1:sha256:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Exercises legacy and fingerprint-bearing novel restore entries. */
class RestoreRepositoryImplTest {
    @Test
    fun `legacy backup position wins over local START`() = runTest {
        withFixture(localIndex = 0, localFraction = 0f) { fixture ->
            fixture.restore(backupIndex = 10, backupFraction = 0.8f)

            assertEquals(ReadingProgress(10, 0, 0.8f, 10.8f / 50f), fixture.progress.get(fixture.chapterId))
            assertEquals(10, fixture.chapterIndex())
        }
    }

    @Test
    fun `matching content preserves the more advanced entire local position`() = runTest {
        withFixture(localIndex = 40, localFraction = 0.2f) { fixture ->
            val localContent = fixture.localContent()
            val backupContent = ChapterContent(localContent.blocks.toList())
            val localFingerprint = fixtureFingerprint(localContent)
            val backupFingerprint = fixtureFingerprint(backupContent)
            assertEquals(50, localContent.blocks.size)
            assertEquals(50, backupContent.blocks.size)
            assertEquals(localFingerprint, backupFingerprint)
            val local = fixture.progress.get(fixture.chapterId)

            fixture.restore(10, 0.8f, backupContent, backupFingerprint)

            assertEquals(local, fixture.progress.get(fixture.chapterId))
            assertEquals(40, fixture.chapterIndex())
        }
    }

    @Test
    fun `matching content preserves the more advanced entire backup position`() = runTest {
        withFixture(localIndex = 10, localFraction = 0.2f) { fixture ->
            val localContent = fixture.localContent()
            val backupContent = ChapterContent(localContent.blocks.toList())
            val localFingerprint = fixtureFingerprint(localContent)
            val backupFingerprint = fixtureFingerprint(backupContent)
            assertEquals(50, localContent.blocks.size)
            assertEquals(50, backupContent.blocks.size)
            assertEquals(localFingerprint, backupFingerprint)

            fixture.restore(40, 0.8f, backupContent, backupFingerprint)

            assertEquals(ReadingProgress(40, 0, 0.8f, 40.8f / 50f), fixture.progress.get(fixture.chapterId))
            assertEquals(40, fixture.chapterIndex())
        }
    }

    @Test
    fun `different fingerprints at the same index preserve the local non START position`() = runTest {
        withFixture(localIndex = 20, localFraction = 0.2f) { fixture ->
            val localContent = fixture.localContent()
            val backupContent = ChapterContent(localContent.blocks.mapIndexed { index, block ->
                if (index == 0) ContentBlock.Paragraph("Changed backup paragraph") else block
            })
            val localFingerprint = fixtureFingerprint(localContent)
            val backupFingerprint = fixtureFingerprint(backupContent)
            assertEquals(50, localContent.blocks.size)
            assertEquals(50, backupContent.blocks.size)
            assertNotEquals(localContent, backupContent)
            assertNotEquals(localFingerprint, backupFingerprint)
            val local = fixture.progress.get(fixture.chapterId)

            fixture.restore(20, 0.8f, backupContent, backupFingerprint)

            assertEquals(local, fixture.progress.get(fixture.chapterId))
            assertEquals(20, fixture.chapterIndex())
            assertEquals(localContent, fixture.localContent(), "a different fingerprint must not overwrite local content")
        }
    }

    @Test
    fun `legacy backup preserves the entire local non START position`() = runTest {
        withFixture(localIndex = 40, localFraction = 0.2f) { fixture ->
            val localContent = fixture.localContent()
            val backupContent = ChapterContent(localContent.blocks.mapIndexed { index, block ->
                if (index == 0) ContentBlock.Paragraph("Changed legacy backup paragraph") else block
            })
            assertNotEquals(localContent, backupContent)
            val local = fixture.progress.get(fixture.chapterId)
            fixture.restore(backupIndex = 10, backupFraction = 0.8f, backupContent = backupContent)

            assertEquals(local, fixture.progress.get(fixture.chapterId), "legacy restore must not mix local index and backup fraction")
            assertEquals(40, fixture.chapterIndex())
            assertEquals(localContent, fixture.localContent(), "a legacy backup must not overwrite local content")
        }
    }

    @Test
    fun `legacy backup and local START leave progress unchanged`() = runTest {
        withFixture(localIndex = 0, localFraction = 0f) { fixture ->
            val local = fixture.progress.get(fixture.chapterId)
            fixture.progress.writes = 0
            fixture.restore(backupIndex = 0, backupFraction = 0f)

            assertEquals(local, fixture.progress.get(fixture.chapterId))
            assertEquals(0, fixture.chapterIndex())
            assertEquals(0, fixture.progress.writes, "restoring START over START must not rewrite progress")
        }
    }

    private suspend fun withFixture(localIndex: Int, localFraction: Float, block: suspend (Fixture) -> Unit) {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        try {
            val database = Database(
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
            val novel = Novel("fixture", "novel", "Fixture novel")
            val manga = NovelMapper.toManga(novel)
            val chapter = NovelMapper.toChapter(novel, NovelChapter("fixture", "novel", "chapter", "Chapter"))
            val mangaId = database.mangaQueries.insertReturningId(
                sourceId = manga.source, remoteUrl = manga.url, remoteTitle = manga.title,
                remoteAuthor = manga.author, remoteArtist = manga.artist, remoteDescription = manga.description,
                remoteGenre = manga.genre, remoteStatus = manga.status, remoteCover = manga.thumbnailUrl,
                remoteUpdateStrategy = manga.updateStrategy, remoteMemo = manga.memo, userFavoriteAt = manga.favoriteAt,
                userNotes = manga.notes, userReaderFlags = manga.viewerFlags, userChapterFlags = manga.chapterFlags,
                stateChapterLastUpdate = manga.lastUpdate, stateChapterNextUpdate = manga.nextUpdate,
                stateChapterFetchInterval = manga.fetchInterval.toLong(), stateCoverLastModified = manga.coverLastModified,
                stateInitialized = manga.initialized,
            ).awaitAsOne()
            val chapterId = database.chapterQueries.insertReturningId(
                mangaId = mangaId, remoteUrl = chapter.url, remoteName = chapter.name,
                remoteScanlator = chapter.scanlator, remoteChapterNumber = chapter.chapterNumber,
                remoteDateUpload = chapter.dateUpload, remoteOrder = chapter.sourceOrder, remoteMemo = chapter.memo,
                userRead = chapter.read, userBookmark = chapter.bookmark, userLastPageRead = localIndex,
                stateDateFetch = chapter.dateFetch,
            ).awaitAsOne()
            val content = ChapterContent((0 until 50).map { ContentBlock.Paragraph("Block $it") })
            val storedContents = mutableMapOf(chapterId to content)
            val contentRepository = mockk<ChapterContentRepository>()
            coEvery { contentRepository.get(any()) } coAnswers {
                storedContents[firstArg<Long>()]
            }
            coEvery { contentRepository.save(any(), any()) } coAnswers {
                storedContents[firstArg<Long>()] = secondArg<ChapterContent>()
                "in-memory fixture"
            }
            val progress = CountingProgress(ReadingProgressRepositoryImpl(database))
            progress.save(chapterId, ReadingProgress(localIndex, 0, localFraction, (localIndex + localFraction) / 50f))
            val chapters = ChapterRepositoryImpl(database, contentRepository, progress)
            val mangaRepository = mockk<MangaRepository>()
            coEvery { mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source) } returns manga.copy(id = mangaId)
            coEvery { mangaRepository.update(any()) } returns true
            val tracks = mockk<TrackRepository>()
            coEvery { tracks.getTracksByMangaId(mangaId) } returns emptyList()
            val restore: RestoreRepository = RestoreRepositoryImpl(database, mangaRepository, chapters, tracks, contentRepository, progress)
            block(Fixture(restore, chapters, progress, manga.copy(id = mangaId), chapter, chapterId, content, contentRepository))
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
        private val repository: RestoreRepository,
        private val chapters: ChapterRepositoryImpl,
        val progress: CountingProgress,
        private val manga: tachiyomi.domain.manga.model.Manga,
        private val chapter: tachiyomi.domain.chapter.model.Chapter,
        val chapterId: Long,
        private val content: ChapterContent,
        private val contentRepository: ChapterContentRepository,
    ) {
        suspend fun chapterIndex() = chapters.getChapterById(chapterId)!!.lastPageRead
        suspend fun localContent() = requireNotNull(contentRepository.get(chapterId))

        suspend fun restore(
            backupIndex: Int,
            backupFraction: Float,
            backupContent: ChapterContent = content,
            backupFingerprint: String? = null,
        ) {
            val entry = RestoredManga(
                manga = manga,
                chapters = listOf(chapter.copy(lastPageRead = backupIndex)),
                categoryIds = emptyList(), history = emptyList(), tracks = emptyList(), excludedScanlators = emptyList(),
                novelPositions = mapOf(chapter.url to backupFraction),
                novelContents = mapOf(chapter.url to Json.encodeToString(ChapterContent.serializer(), backupContent)),
                novelContentFingerprints = if (backupFingerprint == null) emptyMap() else mapOf(chapter.url to backupFingerprint),
            )
            repository.restoreManga(listOf(entry)) { MangaUpdate(it.id) {} }
        }
    }
}
