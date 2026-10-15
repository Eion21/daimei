package eu.kanade.tachiyomi.data.download

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.novel.interactor.GetChapterContent
import tachiyomi.domain.source.service.SourceManager

/**
 * Whether a novel chapter can be handed to the download manager at all.
 *
 * This is the gate the whole unification stands on, and it is not a question about pages: `Downloader.queueChapters`
 * asks the source manager for the manga's source and casts it to `HttpSource` before it will build a single
 * `Download`. A novel source is a plain `Source`, so the cast yields nothing and the method returns `false` with no
 * error, no notification and no log. The button the user taps then does exactly nothing — the same silent no-op this
 * phase exists to remove, one layer earlier than the one already fixed.
 *
 * Nothing here runs a download. The queue is the whole claim: a chapter is either in it, and can be downloaded and
 * indexed and deleted, or it is not, and none of those words apply to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerNovelQueueTest {

    private val provider = mockk<DownloadProvider>(relaxed = true)
    private val sourceManager = mockk<SourceManager>()
    private val manga = Manga.create().copy(id = MANGA_ID, title = MANGA_TITLE, source = SOURCE_ID)

    @BeforeEach
    fun setUp() {
        coEvery { provider.findDownloadedChapterIds(any(), any(), any()) } returns emptySet()
    }

    @Test
    fun `a novel chapter reaches the download queue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloader = downloader()
            coEvery { sourceManager.get(SOURCE_ID) } returns novelSource()

            // `autoStart = true` is what the manga screen, the library, updates and auto-download all pass
            val queued = downloader.queueChapters(manga, listOf(novelChapter()), autoStart = true)

            assertEquals(
                listOf(novelChapter()),
                downloader.queueState.value.map { it.chapter },
                "the chapter was never queued, so nothing downstream can download, index or delete it",
            )
            // What the caller acts on: it is the flag that starts the download worker
            assertEquals(true, queued, "the chapter was queued without saying the worker has to start")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a novel chapter is not queued twice when the queue already holds it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloader = downloader()
            coEvery { sourceManager.get(SOURCE_ID) } returns novelSource()

            downloader.queueChapters(manga, listOf(novelChapter()), autoStart = false)
            val queued = downloader.queueChapters(manga, listOf(novelChapter()), autoStart = true)

            assertEquals(listOf(novelChapter()), downloader.queueState.value.map { it.chapter })
            assertEquals(false, queued, "a second copy of the same chapter was queued")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an image chapter still reaches the download queue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloader = downloader()
            coEvery { sourceManager.get(SOURCE_ID) } returns imageSource()

            downloader.queueChapters(manga, listOf(imageChapter()), autoStart = false)

            assertEquals(listOf(imageChapter()), downloader.queueState.value.map { it.chapter })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a chapter whose source is not installed is still not queued`() = runTest {
        // The other half of the same guard: widening the source type must not turn "no such source" into "queued
        // and then failing", which is the behaviour `as? HttpSource ?: return false` existed to prevent.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloader = downloader()
            coEvery { sourceManager.get(SOURCE_ID) } returns null

            assertEquals(false, downloader.queueChapters(manga, listOf(imageChapter()), autoStart = false))
            assertEquals(emptyList<Chapter>(), downloader.queueState.value.map { it.chapter })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a novel chapter already on disk is not queued a second time`() = runTest {
        // The index answers this for both formats, and it is the check that keeps a re-tap from writing a second
        // copy next to the first.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloader = downloader()
            coEvery { sourceManager.get(SOURCE_ID) } returns novelSource()
            coEvery { provider.findDownloadedChapterIds(any(), any(), any()) } returns setOf(CHAPTER_ID)

            downloader.queueChapters(manga, listOf(novelChapter()), autoStart = false)

            assertEquals(emptyList<Chapter>(), downloader.queueState.value.map { it.chapter })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.downloader(): Downloader = Downloader(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        context = mockk<Context>(relaxed = true),
        provider = provider,
        cache = mockk(relaxed = true),
        sourceManager = sourceManager,
        chapterCache = mockk(relaxed = true),
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        xml = mockk<XML>(relaxed = true),
        getCategories = mockk(relaxed = true),
        getTracks = mockk(relaxed = true),
        store = mockk(relaxed = true),
        notifier = mockk(relaxed = true),
        getChapterContent = mockk<GetChapterContent>(),
    )

    /** A novel source is a plain `Source`. It is not an `HttpSource` and it cannot be one. */
    private fun novelSource(): Source = mockk<Source>(relaxed = true).also {
        every { it.name } returns "Novel Web"
        every { it.lang } returns "es"
    }

    private fun imageSource(): HttpSource = mockk<HttpSource>(relaxed = true).also {
        every { it.name } returns "Manga Web"
        every { it.lang } returns "es"
    }

    private fun novelChapter() = Chapter.create().copy(
        id = CHAPTER_ID,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
        memo = buildJsonObject { put("novelSourceId", JsonPrimitive("demo")) },
    )

    private fun imageChapter() = Chapter.create().copy(
        id = CHAPTER_ID,
        mangaId = MANGA_ID,
        name = CHAPTER_NAME,
        url = CHAPTER_URL,
    )

    private companion object {
        const val MANGA_ID = 1L
        const val SOURCE_ID = 42L
        const val CHAPTER_ID = 3L
        const val MANGA_TITLE = "La torre de cristal"
        const val CHAPTER_NAME = "Capítulo 1"
        const val CHAPTER_URL = "/chapter/1"
    }
}
