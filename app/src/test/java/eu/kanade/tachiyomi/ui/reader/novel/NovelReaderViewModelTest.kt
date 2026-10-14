package eu.kanade.tachiyomi.ui.reader.novel

import app.novel.reader.core.ReadingPosition
import app.novel.runtime.SourceRegistry
import app.novel.source.api.ChapterContent
import app.novel.source.api.ContentBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import kotlin.time.Clock

/**
 * What the reader is handed, and in what order.
 *
 * `NovelReaderScreen` restores as soon as it is given content and cannot restore again until the content identity
 * changes, so the one thing the ViewModel must guarantee is that content never arrives without the saved position
 * already in hand. Everything the reader does afterwards — reporting progress, saving it, marking read — assumes
 * that.
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

        override suspend fun get(chapterId: Long): ChapterContent? = cached[chapterId]
        override fun getAsFlow(chapterId: Long): Flow<ChapterContent?> = emptyFlow()

        override suspend fun save(chapterId: Long, content: ChapterContent): String {
            cached[chapterId] = content
            return "/tmp/chapter_$chapterId.json"
        }

        override suspend fun delete(chapterId: Long) {
            cached -= chapterId
        }
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

    private class FakeChapterRepository : ChapterRepository {
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

        override suspend fun getChapterById(id: Long): Chapter? = null
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

    private class UnusedMangaRepository : MangaRepository {
        override suspend fun getMangaById(id: Long): Manga = throw NotImplementedError()
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
    ): NovelReaderViewModel {
        return NovelReaderViewModel(
            getChapterContent = GetChapterContent(content, chapters, UnusedMangaRepository(), SourceRegistry()),
            getReadingProgress = GetReadingProgress(progress),
            saveReadingProgress = SaveReadingProgress(progress),
            updateChapter = UpdateChapter(chapters),
            upsertHistory = UpsertHistory(history),
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

    private companion object {
        const val CHAPTER_ID = 7L
    }
}
