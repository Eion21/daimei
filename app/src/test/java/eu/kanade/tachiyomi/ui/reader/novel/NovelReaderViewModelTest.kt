package eu.kanade.tachiyomi.ui.reader.novel

import app.novel.reader.core.ReadingPosition
import app.novel.runtime.SourceRegistry
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterSummary
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.novel.interactor.GetReadingProgress
import tachiyomi.domain.novel.interactor.SaveReadingProgress
import tachiyomi.domain.novel.repository.ChapterContentRepository
import tachiyomi.domain.novel.repository.ReadingProgress
import tachiyomi.domain.novel.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import kotlin.time.Clock

/**
 * What the reader is handed, and in what order.
 *
 * `NovelReaderScreen` restores as soon as it is given content and cannot restore again until the content identity
 * changes, so the one thing the ViewModel must guarantee is that content never arrives without the saved position
 * already in hand. Everything the reader does afterwards — reporting progress, saving it, marking read — assumes
 * that.
 *
 * The other half is the state that is not content. A chapter that cannot be opened has to be told apart from one
 * that is still being opened, because both used to arrive as the same null and the reader drew a spinner for either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelReaderViewModelTest {

    private val content = ChapterContent(
        blocks = listOf(
            ContentBlock.Heading("Capítulo 1", level = 1),
            ContentBlock.Paragraph("It was a bright cold day in April."),
        ),
    )

    private val saved = ReadingProgress(blockIndex = 1, blockOffsetPx = 0, blockFraction = 0.25f, percent = 0.5f)
    private val savedPosition = ReadingPosition(blockIndex = 1, blockFraction = 0.25f)

    private class FakeContentRepository(initial: Map<Long, ChapterContent> = emptyMap()) :
        ChapterContentRepository {
        private val cached = initial.toMutableMap()

        /** Seeds what a later read will find, the way an already fetched chapter sits on disk. */
        fun put(chapterId: Long, content: ChapterContent) {
            cached[chapterId] = content
        }

        override suspend fun get(chapterId: Long): ChapterContent? = cached[chapterId]
        override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = emptyFlow()

        override suspend fun save(chapterId: Long, content: ChapterContent): String {
            cached[chapterId] = content
            return "/tmp/chapter_$chapterId.json"
        }

        override suspend fun delete(chapterId: Long) {
            cached -= chapterId
        }

        // The per-manga cached set is the chapter list's business, not this reader view model's: nothing here
        // ever asks for it. Spelled out rather than defaulted on the interface so a fake that needs the
        // behaviour has to say so.
        override fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>> = emptyFlow()
    }

    /**
     * A content store that is itself the failure.
     *
     * `GetChapterContent` resolves a source that is down, a chapter with no row and a memo naming a source this
     * build does not ship into a null and names them in its log only. What is left to escape it is a store that
     * cannot be read at all, and that exception is the one reason the reader can put on screen.
     */
    private class UnreadableContentRepository(private val reason: String) : ChapterContentRepository {
        override suspend fun get(chapterId: Long): ChapterContent? = throw IllegalStateException(reason)
        override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = emptyFlow()

        override suspend fun save(chapterId: Long, content: ChapterContent): String = throw NotImplementedError()
        override suspend fun delete(chapterId: Long): Unit = throw NotImplementedError()
        override fun getCachedChapterIdsByMangaId(mangaId: Long): Flow<Set<Long>> = emptyFlow()
    }

    private class FakeProgressRepository(private var stored: ReadingProgress? = null) : ReadingProgressRepository {
        var getCalls = 0
            private set
        val saved = mutableListOf<Pair<Long, ReadingProgress>>()

        override suspend fun get(chapterId: Long): ReadingProgress? {
            getCalls++
            return stored
        }

        override suspend fun save(chapterId: Long, progress: ReadingProgress) {
            saved += chapterId to progress
            stored = progress
        }

        override suspend fun delete(chapterId: Long) {
            stored = null
        }

        // The chapter list's per-manga read, which this fake has no story for: the reader view model never asks
        // for it. Spelled out rather than defaulted on the interface so a fake that needs the behaviour has to
        // say so.
        override fun getPercentByMangaId(mangaId: Long): Flow<Map<Long, Float>> = emptyFlow()
    }

    private class FakeChapterRepository(private val chapter: Chapter? = null) : ChapterRepository {
        val updates = mutableListOf<ChapterUpdate>()

        override suspend fun update(chapterUpdate: ChapterUpdate) {
            updates += chapterUpdate
        }

        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
            updates += chapterUpdates
        }

        override suspend fun updateFromRemote(
            removedIds: List<Long>,
            added: List<Chapter>,
            updated: List<ChapterRemoteUpdate>,
        ): List<Chapter> = emptyList()

        override suspend fun getChapterById(id: Long): Chapter? = chapter?.takeIf { it.id == id }
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

    private class FakeMangaRepository(private val manga: Manga? = null) : MangaRepository {
        override suspend fun getMangaById(id: Long): Manga = manga ?: throw NotImplementedError()
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

    private class FakeHistoryRepository : HistoryRepository {
        val updates = mutableListOf<HistoryUpdate>()

        override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
            updates += historyUpdate
        }

        override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = emptyFlow()
        override suspend fun getLastHistory(): HistoryWithRelations? = null
        override suspend fun getTotalReadDuration(): Long = 0
        override suspend fun getHistoryByMangaId(mangaId: Long): List<History> = emptyList()
        override suspend fun resetHistory(historyId: Long) = Unit
        override suspend fun resetHistoryByMangaId(mangaId: Long) = Unit
        override suspend fun deleteAllHistory(): Boolean = false
        override suspend fun deleteResetHistory() = Unit
    }

    private fun viewModel(
        content: ChapterContentRepository = FakeContentRepository(mapOf(CHAPTER_ID to this.content)),
        progress: ReadingProgressRepository = FakeProgressRepository(saved),
        chapters: ChapterRepository = FakeChapterRepository(),
        history: HistoryRepository = FakeHistoryRepository(),
        manga: Manga? = null,
        mangas: MangaRepository = FakeMangaRepository(manga),
        sourceManager: SourceManager = mockk(relaxed = true),
        trackChapter: TrackChapter = mockk(relaxed = true),
        trackPreferences: TrackPreferences = TrackPreferences(InMemoryPreferenceStore()),
        incognito: GetIncognitoState = mockk<GetIncognitoState> { coEvery { await(anyNullable()) } returns false },
        appScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        getChaptersByMangaId: GetChaptersByMangaId? = null,
        readerPreferences: ReaderPreferences? = null,
        basePreferences: BasePreferences? = null,
        downloadManager: DownloadManager? = null,
    ): NovelReaderViewModel {
        return NovelReaderViewModel(
            getChapterContent = GetChapterContent(content, chapters, FakeMangaRepository(), SourceRegistry()),
            // The read order asks for the downloaded file before the cache. These chapters were read and never
            // downloaded, so the file step has to answer nothing — and it is pointed at a storage manager with no
            // downloads directory rather than at a mock that would answer anything, so the cache and the source keep
            // being what these tests are actually about. The situation a file does answer is
            // `DownloadedNovelChapterReadTest`, which drives the two halves together.
            downloadProvider = DownloadProvider(
                mockk(relaxed = true),
                mockk<StorageManager>().also { coEvery { it.getDownloadsDirectory() } returns null },
                LibraryPreferences(InMemoryPreferenceStore()),
            ),
            getChapter = GetChapter(chapters),
            getManga = GetManga(mangas),
            sourceManager = sourceManager,
            getReadingProgress = GetReadingProgress(progress),
            saveReadingProgress = SaveReadingProgress(progress),
            updateChapter = UpdateChapter(chapters),
            upsertHistory = UpsertHistory(history),
            trackChapter = trackChapter,
            trackPreferences = trackPreferences,
            getIncognitoState = incognito,
            context = mockk(relaxed = true),
            appScope = appScope,
            getChaptersByMangaId = getChaptersByMangaId,
            readerPreferences = readerPreferences,
            basePreferences = basePreferences,
            downloadManager = downloadManager,
        )
    }

    /**
     * Records `position` at the exact moment `content` is emitted.
     *
     * The collector is unconfined so it runs inside the emission itself rather than a dispatch later, which is
     * what makes the ordering observable instead of inferred.
     */
    private suspend fun observeContentAgainstPosition(
        scope: CoroutineScope,
        viewModel: NovelReaderViewModel,
    ): List<Pair<ChapterContent?, ReadingPosition>> {
        val seen = mutableListOf<Pair<ChapterContent?, ReadingPosition>>()
        scope.launch(UnconfinedTestDispatcher()) {
            viewModel.content.collect { seen += it to viewModel.position.value }
        }
        return seen
    }

    @Test
    fun `toolbar titles reuse the chapter and manga already queried while opening cached content`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = mockk<ChapterRepository> {
                coEvery { getChapterById(CHAPTER_ID) } returns chapter(CHAPTER_ID).copy(name = "C3 Fixture Chapter")
            }
            val mangas = mockk<MangaRepository> {
                coEvery { getMangaById(MANGA_ID) } returns manga().copy(title = "C3 Fixture Work")
            }
            val history = FakeHistoryRepository()
            val progress = FakeProgressRepository(saved)
            val viewModel = viewModel(
                chapters = chapters,
                mangas = mangas,
                history = history,
                progress = progress,
                // A relaxed Source mock enters the download provider's real Dispatchers.IO, outside testScheduler.
                // No installed source keeps this cached-content fixture deterministic, after both metadata reads.
                sourceManager = mockk { coEvery { get(SOURCE_ID) } returns null },
            )

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            coVerify(exactly = 1) { chapters.getChapterById(CHAPTER_ID) }
            coVerify(exactly = 1) { mangas.getMangaById(MANGA_ID) }
            assertEquals(content, viewModel.content.value)
            assertEquals(savedPosition, viewModel.position.value)
            assertEquals(1, history.updates.size)
            assertTrue(progress.saved.isEmpty())
            assertNull(viewModel.error.value)
            assertFalse(viewModel.isLoading.value)
            assertEquals(NovelReaderTitles("C3 Fixture Work", "C3 Fixture Chapter"), viewModel.titles.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the saved position is already in hand when the content is published`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()
            val seen = observeContentAgainstPosition(backgroundScope, viewModel)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            val withContent = seen.filter { it.first != null }
            assertNotNull(withContent.firstOrNull()?.first, "no content was ever published")
            // Content handed over with `ReadingPosition.START` is the whole bug: the reader restores against the
            // start, then reports that start straight back and overwrites the saved progress with it.
            assertEquals(
                listOf(savedPosition),
                withContent.map { it.second }.distinct(),
                "content was published before the saved position was resolved",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loading a chapter with no saved progress still starts at the top`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(progress = FakeProgressRepository(null))

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertEquals(content, viewModel.content.value)
            assertEquals(ReadingPosition.START, viewModel.position.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saveProgress writes the logical position, not a pixel offset`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val progress = FakeProgressRepository(null)
            val viewModel = viewModel(progress = progress)

            viewModel.saveProgress(CHAPTER_ID, ReadingPosition(blockIndex = 2, blockFraction = 0.5f))
            advanceUntilIdle()

            val stored = progress.saved.single()
            assertEquals(CHAPTER_ID, stored.first)
            assertEquals(2, stored.second.blockIndex)
            assertEquals(0.5f, stored.second.blockFraction)
            // A logical position has no pixels: only the Compose layer knows a block's measured height.
            assertEquals(0, stored.second.blockOffsetPx)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saveProgress normalizes a position that came out of range`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val progress = FakeProgressRepository(null)
            val viewModel = viewModel(progress = progress)

            viewModel.saveProgress(CHAPTER_ID, ReadingPosition(blockIndex = -4, blockFraction = 3.7f))
            advanceUntilIdle()

            assertEquals(0, progress.saved.single().second.blockIndex)
            assertEquals(1f, progress.saved.single().second.blockFraction)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `markRead flips the read flag`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = FakeChapterRepository()
            val viewModel = viewModel(chapters = chapters)

            viewModel.load(CHAPTER_ID)
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            val update = chapters.updates.single()
            assertEquals(CHAPTER_ID, update.id)
            assertNotNull(update.read, "the chapter was not marked read")
            assertEquals(true, update.read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `markRead writes a history row the History screen can show`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = FakeHistoryRepository()
            val viewModel = viewModel(history = history)

            val openedAfter = Clock.System.now()
            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // `user_read` is not history: Mihon keeps them apart, and only this upsert reaches the History screen.
            val update = history.updates.single()
            assertEquals(CHAPTER_ID, update.chapterId)
            assertNotNull(update.readAt, "history needs a read timestamp")
            assertTrue(update.sessionReadDuration >= 0)
            // The read time is measured from when the chapter was opened, so it cannot predate the open itself.
            assertTrue(update.readAt >= openedAfter, "readAt ${update.readAt} predates the open at $openedAfter")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `opening a chapter writes history without reaching the end`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = FakeHistoryRepository()
            val viewModel = viewModel(history = history)

            val openedAfter = Clock.System.now()
            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            // Mihon writes the History row when the chapter opens, so a chapter read half way and then backed out
            // of still shows as recently read. Writing only at the end left it invisible on that screen.
            val update = history.updates.single()
            assertEquals(CHAPTER_ID, update.chapterId)
            assertTrue(update.readAt >= openedAfter, "readAt ${update.readAt} predates the open at $openedAfter")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `opening then finishing a chapter does not write history twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = FakeHistoryRepository()
            val viewModel = viewModel(history = history)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // `history.upsert` adds to `read_duration`, so the row written on open must not be written again at the end.
            assertEquals(1, history.updates.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `reaching the end again does not add the read time twice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = FakeHistoryRepository()
            val viewModel = viewModel(history = history)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            // The host reports the end of a chapter on every debounced scroll emission, not once.
            repeat(3) {
                viewModel.markRead(CHAPTER_ID)
                advanceUntilIdle()
            }

            // `history.upsert` adds to `read_duration`, so a second row would double-count the session.
            assertEquals(1, history.updates.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `opening a chapter again allows its history to be written again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val history = FakeHistoryRepository()
            val viewModel = viewModel(history = history)

            viewModel.load(CHAPTER_ID)
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()
            viewModel.load(CHAPTER_ID)
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // The guard is per load, not for the lifetime of the ViewModel.
            assertEquals(2, history.updates.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `marking read does not move the reading position`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val progress = FakeProgressRepository(saved)
            val viewModel = viewModel(progress = progress)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            assertTrue(progress.saved.isEmpty(), "marking read overwrote the saved position")
            assertEquals(savedPosition, viewModel.position.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // Finishing a chapter, which the trackers have to hear about.

    @Test
    fun `finishing a chapter reports it to the tracker with its number`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val trackChapter = mockk<TrackChapter>(relaxed = true)
            val viewModel = viewModel(
                chapters = FakeChapterRepository(chapter(CHAPTER_ID)),
                manga = manga(),
                trackChapter = trackChapter,
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            coVerify(exactly = 1) { trackChapter.await(any(), MANGA_ID, 5.0) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an incognito source is never reported`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = FakeChapterRepository(chapter(CHAPTER_ID))
            val trackChapter = mockk<TrackChapter>(relaxed = true)
            val viewModel = viewModel(
                chapters = chapters,
                manga = manga(),
                trackChapter = trackChapter,
                incognito = mockk<GetIncognitoState> { coEvery { await(anyNullable()) } returns true },
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            coVerify(exactly = 0) { trackChapter.await(any(), any(), any()) }
            // The chapter is still a chapter the reader reached: incognito hides the read, it does not undo it.
            assertEquals(true, chapters.updates.single().read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a chapter whose number was not recognized is never reported`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = FakeChapterRepository(chapter(CHAPTER_ID, chapterNumber = -1.0))
            val trackChapter = mockk<TrackChapter>(relaxed = true)
            val viewModel = viewModel(
                chapters = chapters,
                manga = manga(),
                trackChapter = trackChapter,
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // A source that publishes no chapter numbers reports -1, and there is no number to send to a tracker.
            coVerify(exactly = 0) { trackChapter.await(any(), any(), any()) }
            assertEquals(true, chapters.updates.single().read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `auto tracking off is never reported`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = FakeChapterRepository(chapter(CHAPTER_ID))
            val trackChapter = mockk<TrackChapter>(relaxed = true)
            val trackPreferences = TrackPreferences(InMemoryPreferenceStore()).apply {
                autoUpdateTrack.set(false)
            }
            val viewModel = viewModel(
                chapters = chapters,
                manga = manga(),
                trackChapter = trackChapter,
                trackPreferences = trackPreferences,
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            coVerify(exactly = 0) { trackChapter.await(any(), any(), any()) }
            assertEquals(true, chapters.updates.single().read)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an already read chapter is reported again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val trackChapter = mockk<TrackChapter>(relaxed = true)
            val viewModel = viewModel(
                chapters = FakeChapterRepository(chapter(CHAPTER_ID, read = true)),
                manga = manga(),
                trackChapter = trackChapter,
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // The reader reports the number it was handed; `TrackChapter` is what skips a number the tracker
            // already has, so the already-read flag changes nothing here.
            coVerify(exactly = 1) { trackChapter.await(any(), MANGA_ID, 5.0) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a tracker failure does not keep the chapter from being marked read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chapters = FakeChapterRepository(chapter(CHAPTER_ID))
            val history = FakeHistoryRepository()
            val trackChapter = mockk<TrackChapter>()
            coEvery { trackChapter.await(any(), any(), any()) } throws RuntimeException("tracker is down")
            val viewModel = viewModel(
                chapters = chapters,
                history = history,
                manga = manga(),
                trackChapter = trackChapter,
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

            viewModel.markRead(CHAPTER_ID)
            advanceUntilIdle()

            // A tracker being down is not the reader's problem: the read flag and the History row are already written.
            assertEquals(true, chapters.updates.single().read)
            assertEquals(CHAPTER_ID, history.updates.single().chapterId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** A chapter the tracker can be told about, by default unread and with a number the source recognized. */
    private fun chapter(id: Long, read: Boolean = false, chapterNumber: Double = 5.0) = Chapter.create().copy(
        id = id,
        mangaId = MANGA_ID,
        chapterNumber = chapterNumber,
        read = read,
    )

    /** The manga a chapter belongs to, with a source the incognito check can be pointed at. */
    private fun manga() = Manga.create().copy(id = MANGA_ID, source = SOURCE_ID)

    @Test
    fun `a cached chapter opens with nothing to fall back on`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // What the download badge stopped claiming must stay true: the reader answers from the cache before it
            // asks anything else. `SourceRegistry()` holds no source and the chapter repository has no row, so every
            // step past the cache would miss — and this is the state a chapter the reader only ever read is in.
            val viewModel = viewModel()

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertEquals(content, viewModel.content.value)
            assertNull(viewModel.error.value, "a chapter that opened reported an error")
            assertFalse(viewModel.isLoading.value, "a chapter that opened was still loading")
        } finally {
            Dispatchers.resetMain()
        }
    }

    // A failure, which is not the loading state.

    @Test
    fun `a chapter with no content anywhere fails instead of loading forever`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Nothing cached and no chapter row, so there is nothing to fetch either: this is the offline reader
            // that used to spin on a `CircularProgressIndicator` for as long as the screen was open.
            val viewModel = viewModel(content = FakeContentRepository())

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertNotNull(viewModel.error.value, "an impossible read stayed indistinguishable from one in progress")
            assertNull(viewModel.content.value)
            assertFalse(viewModel.isLoading.value, "the reader would keep spinning over a failure that had landed")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a failure with no reason to name still fails rather than staying silent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // The source being down is the case that reaches the reader most often, and `GetChapterContent` logs
            // the why without handing it over. The reader has to say something anyway, because the alternative is
            // the spinner this replaced.
            val viewModel = viewModel(content = FakeContentRepository())

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            val error = viewModel.error.value
            assertNotNull(error, "a failure with nothing to say did not report one")
            assertNull(error?.reason, "a reason was invented for a failure that has none")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the error carries the reason when the failure has one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(content = UnreadableContentRepository(STORE_FAILURE))

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertEquals(STORE_FAILURE, viewModel.error.value?.reason)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `retrying clears the error and loads the chapter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // The real case: the chapter could not be opened offline, the reader came back online and tapped
            // retry. Same call, so the error has to go before the load rather than after it.
            val store = FakeContentRepository()
            val viewModel = viewModel(content = store)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            assertNotNull(viewModel.error.value, "nothing to retry from")

            store.put(CHAPTER_ID, content)
            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertNull(viewModel.error.value, "the error outlived the load that resolved it")
            assertEquals(content, viewModel.content.value)
            assertFalse(viewModel.isLoading.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a retry that fails again fails again rather than going back to loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Clearing the error on the way in is what makes retry work, and it is also the one place the failure
            // state could be dropped by accident — leaving the reader spinning for good, which is the bug itself.
            val viewModel = viewModel(content = FakeContentRepository())

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertNotNull(viewModel.error.value, "the second failure left the reader loading forever")
            assertNull(viewModel.content.value)
            assertFalse(viewModel.isLoading.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a chapter with no blocks is a failure and not a blank page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // What the device cache still held: `{"blocks":[]}`. `ChapterBody` draws one item per block, so this
            // content is an empty LazyColumn — a black page the reader cannot tell apart from a crash. Even with
            // the source and the cache both refusing to produce one, the reader has to treat it as a failure.
            val history = FakeHistoryRepository()
            val viewModel = viewModel(
                content = FakeContentRepository(mapOf(CHAPTER_ID to ChapterContent(blocks = emptyList()))),
                history = history,
            )

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertNull(viewModel.content.value, "a chapter with no blocks was published as something to read")
            assertNotNull(viewModel.error.value, "a chapter with no blocks reported no failure")
            assertTrue(history.updates.isEmpty(), "a chapter nobody could read was written to History")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a chapter whose content failed to load writes no history`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // History describes something the reader reached, and a chapter that never opened was not reached. This
            // is the same rule as the one that made Mihon write history on open rather than only at the end.
            val history = FakeHistoryRepository()
            val viewModel = viewModel(content = FakeContentRepository(), history = history)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            assertNotNull(viewModel.error.value, "nothing failed, so this pinned nothing")
            assertTrue(history.updates.isEmpty(), "a chapter nobody could read was written to History")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a chapter that opens after a failed attempt writes its history once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // The retry is not a lesser open: once the chapter resolves it is a chapter the reader reached, so it
            // belongs in History. Writing it here is also the only thing the failed attempt left undone.
            val store = FakeContentRepository()
            val history = FakeHistoryRepository()
            val viewModel = viewModel(content = store, history = history)

            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()
            store.put(CHAPTER_ID, content)
            viewModel.load(CHAPTER_ID)
            advanceUntilIdle()

            val update = history.updates.single()
            assertEquals(CHAPTER_ID, update.chapterId)
            assertNull(viewModel.error.value, "the retry failed instead of opening the chapter")
            assertEquals(content, viewModel.content.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Separate per-id stores: saving OLD must never mutate the saved row for NEXT. No real IO or trackers. */
    private inner class TransitionFixture(
        val rows: List<Chapter> = transitionRows(),
        val work: Manga = manga().copy(title = "Transition Work"),
        downloadedIds: Set<Long> = emptySet(),
    ) {
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val mangas = mockk<MangaRepository>()
        val progress = mockk<ReadingProgressRepository>()
        val contentStore = mockk<ChapterContentRepository>()
        val history = mockk<HistoryRepository>()
        val tracker = mockk<TrackChapter>()
        val downloads = mockk<DownloadManager>(relaxed = true)
        val readerPrefs = ReaderPreferences(InMemoryPreferenceStore())
        // Stub only the dependency's preference handle, avoiding Android installer/GLES initialization.
        // This is an isolated in-memory fixture key, never an installed app preference.
        val basePrefs = mockk<BasePreferences> {
            every { downloadedOnly } returns InMemoryPreferenceStore().getBoolean("c5_fixture_downloaded_only", false)
        }
        val oldPosition = ReadingPosition(1, 0.75f)
        val nextSaved = ReadingProgress(2, 0, 0.625f, 0.65625f)
        val nextPosition = ReadingPosition(2, 0.625f)
        val nextContent = ChapterContent(List(4) { ContentBlock.Paragraph("Next paragraph $it") })
        val thirdContent = ChapterContent(listOf(ContentBlock.Paragraph("Third chapter")))
        val saves = mutableListOf<Pair<Long, ReadingProgress>>()
        val updates = mutableListOf<ChapterUpdate>()
        val histories = mutableListOf<HistoryUpdate>()
        val tracks = mutableListOf<Pair<Long, Double>>()
        val events = mutableListOf<String>()
        var nextLoader: suspend () -> ChapterContent? = { nextContent }
        var beforeUpdate: suspend (ChapterUpdate) -> Unit = {}

        init {
            coEvery { chapters.getChapterById(any()) } answers {
                rows.find { it.id == firstArg<Long>() }
            }
            coEvery { chapters.getChapterByMangaId(MANGA_ID, true) } returns rows
            coEvery { chapters.update(any()) } coAnswers {
                val update = firstArg<ChapterUpdate>()
                beforeUpdate(update)
                updates += update
            }
            coEvery { mangas.getMangaById(MANGA_ID) } returns work
            coEvery { progress.get(any()) } answers {
                events += "restore:${firstArg<Long>()}"
                when (firstArg<Long>()) {
                    CHAPTER_ID -> saved
                    NEXT_ID -> nextSaved
                    else -> null
                }
            }
            coEvery { progress.save(any(), any()) } answers {
                saves += firstArg<Long>() to secondArg<ReadingProgress>()
                events += "save:${firstArg<Long>()}"
            }
            coEvery { contentStore.get(any()) } coAnswers {
                val id = firstArg<Long>()
                events += "load:$id"
                when (id) {
                    CHAPTER_ID -> content
                    NEXT_ID -> nextLoader()
                    else -> thirdContent
                }
            }
            coEvery { history.upsertHistory(any()) } answers { histories += firstArg<HistoryUpdate>() }
            coEvery { tracker.await(any(), any(), any(), any()) } answers {
                tracks += secondArg<Long>() to thirdArg<Double>()
            }
            every { downloads.getDownloadedChapterIds(any(), any()) } returns downloadedIds
            every { downloads.isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
                rows.any { it.name == firstArg<String>() && it.id in downloadedIds }
            }
        }

        fun vm() = viewModel(
            content = contentStore,
            progress = progress,
            chapters = chapters,
            mangas = mangas,
            history = history,
            trackChapter = tracker,
            trackPreferences = TrackPreferences(InMemoryPreferenceStore()).apply { autoUpdateTrack.set(true) },
            sourceManager = mockk<SourceManager>().also { manager -> coEvery { manager.get(any()) } returns null },
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            readerPreferences = readerPrefs,
            basePreferences = basePrefs,
            downloadManager = downloads,
        )

        fun assertNoCompletion() {
            assertTrue(updates.isEmpty(), "opening or failing NEXT marked a chapter read")
            assertTrue(tracks.isEmpty(), "opening or failing NEXT called a tracker")
        }
    }

    private fun transitionRows() = listOf(
        chapter(THIRD_ID, chapterNumber = 7.0).copy(name = "Chapter 7", sourceOrder = 1),
        chapter(NEXT_ID, chapterNumber = 6.0).copy(name = "Chapter 6", sourceOrder = 2),
        chapter(CHAPTER_ID).copy(name = "Chapter 5", sourceOrder = 3),
    )

    private fun transitionTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun assertOldPublished(vm: NovelReaderViewModel) {
        assertEquals(content, vm.content.value)
        assertEquals(savedPosition, vm.position.value)
        assertEquals(NovelReaderTitles("Transition Work", "Chapter 5"), vm.titles.value)
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
    }

    @Test
    fun s31RecreatedHostDoesNotReloadRouteOrResetNavigatedChapter() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.attach(CHAPTER_ID)
        vm.attach(CHAPTER_ID)
        advanceUntilIdle()
        coVerify(exactly = 1) { f.contentStore.get(CHAPTER_ID) }
        vm.saveProgress(CHAPTER_ID, f.oldPosition)
        advanceUntilIdle()
        vm.attach(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(f.oldPosition, vm.position.value)
        coVerify(exactly = 1) { f.contentStore.get(CHAPTER_ID) }
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        vm.attach(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(f.nextContent, vm.content.value)
        coVerify(exactly = 1) { f.contentStore.get(NEXT_ID) }
        assertEquals(listOf(CHAPTER_ID, NEXT_ID), f.histories.map { it.chapterId })
        f.assertNoCompletion()
    }

    @Test
    fun s31ProgressWritesStayOrderedWhenStorageSuspends() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        val resume = CompletableDeferred<Unit>()
        val stored = mutableListOf<ReadingProgress>()
        coEvery { f.progress.save(any(), any()) } coAnswers {
            val progress = secondArg<ReadingProgress>()
            if (progress.blockFraction == 0.25f) resume.await()
            stored += progress
        }
        vm.saveProgress(CHAPTER_ID, ReadingPosition(1, 0.25f))
        runCurrent()
        vm.saveProgress(CHAPTER_ID, ReadingPosition(1, 0.75f))
        runCurrent()
        resume.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0.25f, 0.75f), stored.map { it.blockFraction })
        assertEquals(ReadingPosition(1, 0.75f), vm.position.value)
        f.assertNoCompletion()
    }

    @Test
    fun s31FinalProgressSurvivesViewModelClear() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        val resume = CompletableDeferred<Unit>()
        val persisted = mutableListOf<ReadingProgress>()
        coEvery { f.progress.save(any(), any()) } coAnswers {
            resume.await()
            persisted += secondArg<ReadingProgress>()
        }
        vm.saveProgress(CHAPTER_ID, f.oldPosition)
        runCurrent()
        androidx.lifecycle.ViewModelStore().apply { put("s31", vm); clear() }
        resume.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0.75f), persisted.map { it.blockFraction })
    }

    @Test
    fun s31PreviousPolicyUsesSameOrderAndDownloadedFilterAsNext() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(NEXT_ID)
        advanceUntilIdle()
        assertEquals(CHAPTER_ID, vm.navigation.value?.previous?.id)
        assertEquals(THIRD_ID, vm.navigation.value?.next?.id)
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertNull(vm.navigation.value?.previous)
        f.basePrefs.downloadedOnly.set(true)
        vm.load(THIRD_ID)
        advanceUntilIdle()
        assertNull(vm.navigation.value?.previous)
        f.assertNoCompletion()
    }

    @Test
    fun s31PreviousNextRoundTripRestoresEachChaptersOwnPosition() = transitionTest {
        val f = TransitionFixture()
        val positions = mutableMapOf(CHAPTER_ID to saved, NEXT_ID to f.nextSaved)
        coEvery { f.progress.get(any()) } answers { positions[firstArg<Long>()] }
        coEvery { f.progress.save(any(), any()) } answers {
            positions[firstArg<Long>()] = secondArg<ReadingProgress>()
            f.saves += firstArg<Long>() to secondArg<ReadingProgress>()
        }
        val vm = f.vm()
        vm.attach(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(f.nextPosition, vm.position.value)
        val nextStop = ReadingPosition(3, 0.375f)
        vm.openPreviousChapter(nextStop)
        advanceUntilIdle()
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
        assertEquals(f.oldPosition, vm.position.value)
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(nextStop, vm.position.value)
        assertEquals(listOf(CHAPTER_ID, NEXT_ID, CHAPTER_ID), f.saves.map { it.first })
        assertEquals(0.375f, positions[NEXT_ID]?.blockFraction)
        f.assertNoCompletion()
    }

    @Test
    fun s31RapidNewRouteWinsOverAnOlderSuspendedAdjacentLoad() = transitionTest {
        val f = TransitionFixture()
        val gate = CompletableDeferred<Unit>()
        f.nextLoader = { gate.await(); f.nextContent }
        val vm = f.vm()
        vm.attach(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        runCurrent()
        vm.attach(THIRD_ID)
        runCurrent()
        assertEquals(THIRD_ID, vm.activeChapterId.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(THIRD_ID, vm.activeChapterId.value)
        assertEquals(f.thirdContent, vm.content.value)
        assertEquals(ReadingPosition.START, vm.position.value)
        assertEquals(listOf(CHAPTER_ID, THIRD_ID), f.histories.map { it.chapterId })
        assertFalse(vm.isTransitioning.value)
        f.assertNoCompletion()
    }

    @Test
    fun s31FailedPreviousKeepsCurrentChapterAndRetriesTheSameDirection() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.attach(NEXT_ID)
        advanceUntilIdle()
        coEvery { f.contentStore.get(CHAPTER_ID) } throws IllegalStateException(STORE_FAILURE)
        vm.openPreviousChapter(f.nextPosition)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(NovelChapterError(STORE_FAILURE), vm.previousChapterError.value)
        assertNull(vm.nextChapterError.value)
        assertEquals(listOf(NEXT_ID), f.histories.map { it.chapterId })
        coEvery { f.contentStore.get(CHAPTER_ID) } returns content
        vm.openPreviousChapter(f.nextPosition)
        advanceUntilIdle()
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
        assertNull(vm.previousChapterError.value)
        assertEquals(savedPosition, vm.position.value)
        f.assertNoCompletion()
    }

    @Test
    fun c5InitialSourceAscendingPublishesIdentityAndNext() = transitionTest {
        val f = TransitionFixture(work = manga().copy(
            title = "Transition Work", chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_ASC,
        ))
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertOldPublished(vm)
        assertEquals(NEXT_ID, vm.navigation.value?.next?.id)
        assertEquals(NovelNavigationStatus.NEXT, vm.navigation.value?.status)
        assertFalse(vm.navigation.value?.downloadedOnly ?: true)
        assertFalse(f.readerPrefs.skipDupe.get())
        assertTrue(f.saves.isEmpty())
        f.assertNoCompletion()
        coVerify(exactly = 1) { f.chapters.getChapterById(any()) }
        coVerify(exactly = 1) { f.mangas.getMangaById(any()) }
        coVerify(exactly = 1) { f.chapters.getChapterByMangaId(MANGA_ID, true) }
        coVerify(exactly = 1) { f.chapters.getChapterByMangaId(any(), any()) }
    }

    @Test
    fun c5InitialSourceDescendingStillAdvancesInReadingOrder() = transitionTest {
        val f = TransitionFixture(work = manga().copy(
            chapterFlags = Manga.CHAPTER_SORTING_SOURCE or Manga.CHAPTER_SORT_DESC,
        ))
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.navigation.value?.next?.id)
        assertEquals(listOf(CHAPTER_ID, NEXT_ID, THIRD_ID), vm.navigation.value?.chapters?.map { it.id })
        f.assertNoCompletion()
    }

    @Test
    fun c5LastChapterPublishesNoMoreStatus() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(THIRD_ID)
        advanceUntilIdle()
        assertEquals(NovelNavigationStatus.LAST_CHAPTER, vm.navigation.value?.status)
        assertNull(vm.navigation.value?.next)
        assertEquals(THIRD_ID, vm.activeChapterId.value)
        f.assertNoCompletion()
    }

    @Test
    fun c5NumericUnknownPublishesAmbiguityStatus() = transitionTest {
        val rows = transitionRows().map { if (it.id == NEXT_ID) it.copy(chapterNumber = -1.0) else it }
        val f = TransitionFixture(rows, manga().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER))
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(NovelNavigationStatus.UNKNOWN_NUMBER, vm.navigation.value?.status)
        assertNull(vm.navigation.value?.next)
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
        f.assertNoCompletion()
    }

    @Test
    fun c5DownloadedOnlyPublishesDestinationAndNoMoreStatus() = transitionTest {
        val f = TransitionFixture(downloadedIds = setOf(THIRD_ID))
        f.basePrefs.downloadedOnly.set(true)
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(THIRD_ID, vm.navigation.value?.next?.id)
        assertEquals(true, vm.navigation.value?.downloadedOnly)
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
        vm.load(THIRD_ID)
        advanceUntilIdle()
        assertEquals(NovelNavigationStatus.NO_MORE_DOWNLOADED, vm.navigation.value?.status)
        assertNull(vm.navigation.value?.next)
        f.assertNoCompletion()
    }

    @Test
    fun c5LocalChaptersAreOfflineDestinationsWithoutDownloadMembership() = transitionTest {
        val f = TransitionFixture(work = manga().copy(source = LocalSource.ID))
        f.basePrefs.downloadedOnly.set(true)
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.navigation.value?.next?.id)
        assertEquals(NovelNavigationStatus.NEXT, vm.navigation.value?.status)
        assertEquals(true, vm.navigation.value?.downloadedOnly)
        f.assertNoCompletion()
    }

    @Test
    fun c5SkipReadPreferenceActuallyFeedsThePolicy() = transitionTest {
        val f = TransitionFixture(rows = transitionRows().map { it.copy(read = it.id == NEXT_ID) })
        f.readerPrefs.skipRead.set(true)
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(THIRD_ID, vm.navigation.value?.next?.id)
        assertEquals(CHAPTER_ID, vm.activeChapterId.value)
        f.assertNoCompletion()
    }

    @Test
    fun c5SkipFilteredPreferenceUsesScanlatorFilteredSnapshot() = transitionTest {
        val rows = transitionRows().map { it.copy(bookmark = it.id == THIRD_ID) }
        val f = TransitionFixture(rows, manga().copy(chapterFlags = Manga.CHAPTER_SHOW_BOOKMARKED))
        f.readerPrefs.skipFiltered.set(true)
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(THIRD_ID, vm.navigation.value?.next?.id)
        assertEquals(listOf(CHAPTER_ID, THIRD_ID), vm.navigation.value?.chapters?.map { it.id })
        coVerify(exactly = 1) { f.chapters.getChapterByMangaId(MANGA_ID, true) }
        f.assertNoCompletion()
    }

    @Test
    fun c5DuplicatePreferenceIsOffByDefaultAndOptInChangesNavigation() = transitionTest {
        val rows = transitionRows().map { if (it.id == NEXT_ID) it.copy(chapterNumber = 5.0) else it }
        val off = TransitionFixture(rows)
        val defaultVm = off.vm()
        defaultVm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(NEXT_ID, defaultVm.navigation.value?.next?.id)
        assertFalse(off.readerPrefs.skipDupe.get())
        val on = TransitionFixture(rows)
        on.readerPrefs.skipDupe.set(true)
        val optedInVm = on.vm()
        optedInVm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(THIRD_ID, optedInVm.navigation.value?.next?.id)
        off.assertNoCompletion()
        on.assertNoCompletion()
    }

    @Test
    fun c5NextSavesLatestOldPositionBeforeLoaderAndRetainsPublishedChapterWhilePaused() = transitionTest {
        val f = TransitionFixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.nextLoader = { entered.complete(Unit); resume.await(); f.nextContent }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        f.events.clear()
        try {
            vm.openNextChapter(f.oldPosition)
            runCurrent()
            assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first }, "NEXT did not save OLD exactly once")
            assertEquals(ReadingProgress(1, 0, 0.75f, 0.875f), f.saves.single().second)
            assertTrue(entered.isCompleted, "NEXT never reached the controlled loader")
            assertTrue(f.events.indexOf("save:$CHAPTER_ID") < f.events.indexOf("load:$NEXT_ID"))
            assertOldPublished(vm)
            assertTrue(vm.isTransitioning.value)
            assertFalse(vm.isLoading.value, "existing reader must not be replaced by an initial spinner")
            f.assertNoCompletion()
        } finally {
            resume.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(f.nextPosition, vm.position.value)
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(NovelReaderTitles("Transition Work", "Chapter 6"), vm.titles.value)
        assertFalse(vm.isTransitioning.value)
        assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first }, "NEXT's saved row was overwritten")
        f.assertNoCompletion()
    }

    @Test
    fun c5UnreadNextPositionAndIdentityAreResolvedBeforeContentPublication() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        val publications = mutableListOf<Triple<ReadingPosition, Long?, NovelReaderTitles>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.content.collect {
                if (it == f.nextContent) publications += Triple(vm.position.value, vm.activeChapterId.value, vm.titles.value)
            }
        }
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(listOf(Triple(f.nextPosition, NEXT_ID, NovelReaderTitles("Transition Work", "Chapter 6"))), publications)
        assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first })
        coVerify(exactly = 1) { f.progress.get(NEXT_ID) }
        f.assertNoCompletion()
    }

    @Test
    fun c5AlreadyReadNextStartsAtTopWithoutOverwritingSavedRow() = transitionTest {
        val f = TransitionFixture(rows = transitionRows().map { it.copy(read = it.id == NEXT_ID) })
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(ReadingPosition.START, vm.position.value)
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first })
        f.assertNoCompletion()
    }

    @Test
    fun c5RepeatedCompletionMarksAndTracksCurrentExactlyOnce() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        repeat(3) { vm.markRead(CHAPTER_ID) }
        advanceUntilIdle()
        assertEquals(listOf(CHAPTER_ID), f.updates.map { it.id })
        assertEquals(listOf(true), f.updates.map { it.read })
        assertEquals(listOf(MANGA_ID to 5.0), f.tracks)
        assertEquals(listOf(CHAPTER_ID), f.histories.map { it.chapterId })
        assertTrue(f.saves.isEmpty())
        assertEquals(savedPosition, vm.position.value)
    }

    @Test
    fun c5CallbacksAfterTransitionUsePublishedIdAndRejectStaleRouteId() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        val savesBefore = f.saves.toList()
        val historyBefore = f.histories.toList()
        val otherStateBefore = listOf(
            vm.activeChapterId.value, vm.navigation.value, vm.error.value, vm.nextChapterError.value,
            vm.isLoading.value, vm.isTransitioning.value,
        )
        vm.saveProgress(CHAPTER_ID, ReadingPosition.START)
        vm.markRead(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(savesBefore, f.saves)
        assertEquals(historyBefore, f.histories)
        assertEquals(
            otherStateBefore,
            listOf(
                vm.activeChapterId.value, vm.navigation.value, vm.error.value, vm.nextChapterError.value,
                vm.isLoading.value, vm.isTransitioning.value,
            ),
        )
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(f.nextPosition, vm.position.value)
        assertEquals(NovelReaderTitles("Transition Work", "Chapter 6"), vm.titles.value)
        f.assertNoCompletion()
        vm.saveProgress(NEXT_ID, ReadingPosition(3, 0.5f))
        repeat(2) { vm.markRead(NEXT_ID) }
        advanceUntilIdle()
        assertEquals(NEXT_ID, f.saves.last().first)
        assertEquals(ReadingProgress(3, 0, 0.5f, 0.875f), f.saves.last().second)
        assertEquals(listOf(NEXT_ID), f.updates.map { it.id })
        assertEquals(listOf(true), f.updates.map { it.read })
        assertEquals(listOf(MANGA_ID to 6.0), f.tracks)
        assertEquals(listOf(CHAPTER_ID, NEXT_ID), f.histories.map { it.chapterId })
    }

    @Test
    fun c5DelayedCompletionCannotWriteOntoNewActiveChapter() = transitionTest {
        val f = TransitionFixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.beforeUpdate = { entered.complete(Unit); resume.await() }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        try {
            vm.markRead(CHAPTER_ID)
            runCurrent()
            assertTrue(entered.isCompleted)
            vm.openNextChapter(f.oldPosition)
            runCurrent()
            assertEquals(NEXT_ID, vm.activeChapterId.value)
            assertEquals(f.nextContent, vm.content.value)
            assertTrue(f.updates.isEmpty())
        } finally {
            resume.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(listOf(CHAPTER_ID), f.updates.map { it.id })
        assertEquals(listOf(true), f.updates.map { it.read })
        assertEquals(listOf(MANGA_ID to 5.0), f.tracks)
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(f.nextPosition, vm.position.value)
        assertEquals(NovelReaderTitles("Transition Work", "Chapter 6"), vm.titles.value)
        assertEquals(listOf(CHAPTER_ID, NEXT_ID), f.histories.map { it.chapterId })
    }

    @Test
    fun c5FailedNextRetainsContentPositionAnchorTitlesAndOffersRetryWithoutCompletion() = transitionTest {
        val f = TransitionFixture()
        f.nextLoader = { throw IllegalStateException(STORE_FAILURE) }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(NovelChapterError(STORE_FAILURE), vm.nextChapterError.value)
        assertOldPublished(vm)
        assertNull(vm.error.value, "NEXT failure replaced the readable chapter with an initial-load error")
        assertFalse(vm.isLoading.value)
        assertFalse(vm.isTransitioning.value)
        assertEquals(NEXT_ID, vm.navigation.value?.next?.id, "retry lost its destination")
        assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first })
        assertEquals(ReadingProgress(1, 0, 0.75f, 0.875f), f.saves.single().second)
        assertEquals(listOf(CHAPTER_ID), f.histories.map { it.chapterId })
        f.assertNoCompletion()
    }

    @Test
    fun c5MissingOfflineNextRetainsUnreadCurrentAndNoDestinationHistory() = transitionTest {
        val f = TransitionFixture()
        // No downloaded file, no cached text and no registered network source: a normal recoverable miss.
        f.nextLoader = { null }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(NovelChapterError(null), vm.nextChapterError.value)
        assertOldPublished(vm)
        assertNull(vm.error.value)
        assertFalse(vm.isTransitioning.value)
        assertEquals(NEXT_ID, vm.navigation.value?.next?.id)
        assertEquals(listOf(CHAPTER_ID), f.histories.map { it.chapterId })
        assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first })
        assertEquals(ReadingProgress(1, 0, 0.75f, 0.875f), f.saves.single().second)
        f.assertNoCompletion()
    }

    @Test
    fun c5RetryNextActuallyLoadsSameDestinationAndClearsTransitionError() = transitionTest {
        val f = TransitionFixture()
        f.nextLoader = { throw IllegalStateException(STORE_FAILURE) }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertNotNull(vm.nextChapterError.value)
        f.nextLoader = { f.nextContent }
        vm.openNextChapter(f.oldPosition)
        advanceUntilIdle()
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(f.nextPosition, vm.position.value)
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertNull(vm.nextChapterError.value)
        assertNull(vm.error.value)
        assertFalse(vm.isTransitioning.value)
        assertTrue(f.saves.all { it.first == CHAPTER_ID })
        assertEquals(listOf(CHAPTER_ID, NEXT_ID), f.histories.map { it.chapterId })
        coVerify(exactly = 2) { f.contentStore.get(NEXT_ID) }
        f.assertNoCompletion()
    }

    @Test
    fun c5ReentrantNextWhileInFlightLoadsAndSavesOnlyOnce() = transitionTest {
        val f = TransitionFixture()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.nextLoader = { entered.complete(Unit); resume.await(); f.nextContent }
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        try {
            vm.openNextChapter(f.oldPosition)
            vm.openNextChapter(ReadingPosition.START)
            runCurrent()
            assertTrue(entered.isCompleted)
            vm.openNextChapter(ReadingPosition.START)
            runCurrent()
            assertEquals(listOf(CHAPTER_ID), f.saves.map { it.first })
            assertEquals(ReadingProgress(1, 0, 0.75f, 0.875f), f.saves.single().second)
            coVerify(exactly = 1) { f.contentStore.get(NEXT_ID) }
            assertOldPublished(vm)
            f.assertNoCompletion()
        } finally {
            resume.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(f.nextContent, vm.content.value)
        assertEquals(NEXT_ID, vm.activeChapterId.value)
        assertEquals(f.nextPosition, vm.position.value)
        assertEquals(listOf(CHAPTER_ID, NEXT_ID), f.histories.map { it.chapterId })
        f.assertNoCompletion()
    }

    @Test
    fun c5OpeningWithoutCompletionStillDoesNotMarkOrTrack() = transitionTest {
        val f = TransitionFixture()
        val vm = f.vm()
        vm.load(CHAPTER_ID)
        advanceUntilIdle()
        assertEquals(content, vm.content.value)
        assertEquals(savedPosition, vm.position.value)
        assertEquals(NovelReaderTitles("Transition Work", "Chapter 5"), vm.titles.value)
        assertEquals(listOf(CHAPTER_ID), f.histories.map { it.chapterId })
        assertTrue(f.saves.isEmpty())
        f.assertNoCompletion()
    }

    private companion object {
        const val CHAPTER_ID = 7L
        const val NEXT_ID = 8L
        const val THIRD_ID = 9L
        const val MANGA_ID = 42L
        const val SOURCE_ID = 99L

        /** Stands in for a content store that cannot be read, which is the one failure that carries a message. */
        const val STORE_FAILURE = "the chapter content store is unavailable"
    }
}
