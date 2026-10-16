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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    private companion object {
        const val CHAPTER_ID = 7L

        /** Stands in for a content store that cannot be read, which is the one failure that carries a message. */
        const val STORE_FAILURE = "the chapter content store is unavailable"
    }
}
